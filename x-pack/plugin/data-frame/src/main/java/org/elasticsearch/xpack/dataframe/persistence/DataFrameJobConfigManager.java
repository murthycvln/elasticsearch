/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.dataframe.persistence;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteAction;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetAction;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.xpack.core.dataframe.DataFrameMessages;
import org.elasticsearch.xpack.dataframe.job.DataFrameJobConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.xpack.core.ClientHelper.DATA_FRAME_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

public class DataFrameJobConfigManager {

    private static final Logger logger = LogManager.getLogger(DataFrameJobConfigManager.class);

    public static final Map<String, String> TO_XCONTENT_PARAMS;
    static {
        Map<String, String> modifiable = new HashMap<>();
        modifiable.put("for_internal_storage", "true");
        TO_XCONTENT_PARAMS = Collections.unmodifiableMap(modifiable);
    }

    private final Client client;
    private final NamedXContentRegistry xContentRegistry;

    public DataFrameJobConfigManager(Client client, NamedXContentRegistry xContentRegistry) {
        this.client = client;
        this.xContentRegistry = xContentRegistry;
    }

    public void putJobConfiguration(DataFrameJobConfig jobConfig, ActionListener<Boolean> listener) {
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            XContentBuilder source = jobConfig.toXContent(builder, new ToXContent.MapParams(TO_XCONTENT_PARAMS));

            IndexRequest indexRequest = new IndexRequest(DataFrameInternalIndex.INDEX_NAME)
                    .opType(DocWriteRequest.OpType.CREATE)
                    .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                    .id(DataFrameJobConfig.documentId(jobConfig.getId()))
                    .source(source);

            executeAsyncWithOrigin(client, DATA_FRAME_ORIGIN, IndexAction.INSTANCE, indexRequest, ActionListener.wrap(r -> {
                listener.onResponse(true);
            }, e -> {
                if (e instanceof VersionConflictEngineException) {
                    // the job already exists
                    listener.onFailure(new ResourceAlreadyExistsException(
                            DataFrameMessages.getMessage(DataFrameMessages.REST_PUT_DATA_FRAME_JOB_EXISTS, jobConfig.getId())));
                } else {
                    listener.onFailure(new RuntimeException(DataFrameMessages.REST_PUT_DATA_FRAME_FAILED_PERSIST_JOB_CONFIGURATION, e));
                }
            }));
        } catch (IOException e) {
            // not expected to happen but for the sake of completeness
            listener.onFailure(new ElasticsearchParseException(
                    DataFrameMessages.getMessage(DataFrameMessages.REST_DATA_FRAME_FAILED_TO_SERIALIZE_JOB, jobConfig.getId()), e));
        }
    }

    public void getJobConfiguration(String jobId, ActionListener<DataFrameJobConfig> resultListener) {
        GetRequest getRequest = new GetRequest(DataFrameInternalIndex.INDEX_NAME, DataFrameJobConfig.documentId(jobId));
        executeAsyncWithOrigin(client, DATA_FRAME_ORIGIN, GetAction.INSTANCE, getRequest, ActionListener.wrap(getResponse -> {

            if (getResponse.isExists() == false) {
                resultListener.onFailure(new ResourceNotFoundException(
                        DataFrameMessages.getMessage(DataFrameMessages.REST_DATA_FRAME_UNKNOWN_JOB, jobId)));
                return;
            }
            BytesReference source = getResponse.getSourceAsBytesRef();
            parseJobLenientlyFromSource(source, jobId, resultListener);
        }, e -> {
            if (e.getClass() == IndexNotFoundException.class) {
                resultListener.onFailure(
                        new ResourceNotFoundException(DataFrameMessages.getMessage(DataFrameMessages.REST_DATA_FRAME_UNKNOWN_JOB, jobId)));
            } else {
                resultListener.onFailure(e);
            }
        }));
    }

    public void deleteJobConfiguration(String jobId, ActionListener<Boolean> listener) {
        DeleteRequest request = new DeleteRequest(DataFrameInternalIndex.INDEX_NAME, DataFrameJobConfig.documentId(jobId));
        request.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        executeAsyncWithOrigin(client, DATA_FRAME_ORIGIN, DeleteAction.INSTANCE, request, ActionListener.wrap(deleteResponse -> {

            if (deleteResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                listener.onFailure(
                        new ResourceNotFoundException(DataFrameMessages.getMessage(DataFrameMessages.REST_DATA_FRAME_UNKNOWN_JOB, jobId)));
                return;
            }
            listener.onResponse(true);
        }, e -> {
            if (e.getClass() == IndexNotFoundException.class) {
                listener.onFailure(
                        new ResourceNotFoundException(DataFrameMessages.getMessage(DataFrameMessages.REST_DATA_FRAME_UNKNOWN_JOB, jobId)));
            } else {
                listener.onFailure(e);
            }
        }));
    }

    private void parseJobLenientlyFromSource(BytesReference source, String jobId, ActionListener<DataFrameJobConfig> jobListener)  {
        try (InputStream stream = source.streamInput();
             XContentParser parser = XContentFactory.xContent(XContentType.JSON)
                     .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, stream)) {
            jobListener.onResponse(DataFrameJobConfig.PARSER.parse(parser, jobId));
        } catch (Exception e) {
            logger.error(DataFrameMessages.getMessage(DataFrameMessages.FAILED_TO_PARSE_JOB_CONFIGURATION, jobId), e);
            jobListener.onFailure(e);
        }
    }
}
