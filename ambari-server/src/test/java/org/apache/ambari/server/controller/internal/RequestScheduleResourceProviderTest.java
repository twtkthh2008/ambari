/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.controller.internal;

import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.RequestScheduleResponse;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.utilities.PredicateBuilder;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.scheduler.ExecutionScheduleManager;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.scheduler.Batch;
import org.apache.ambari.server.state.scheduler.BatchRequest;
import org.apache.ambari.server.state.scheduler.RequestExecution;
import org.apache.ambari.server.state.scheduler.RequestExecutionFactory;
import org.apache.ambari.server.state.scheduler.Schedule;
import org.easymock.Capture;
import org.easymock.IAnswer;
import org.junit.Test;

import junit.framework.Assert;

public class RequestScheduleResourceProviderTest {

  RequestScheduleResourceProvider getResourceProvider
    (AmbariManagementController managementController) {

    Resource.Type type = Resource.Type.RequestSchedule;

    return (RequestScheduleResourceProvider)
      AbstractControllerResourceProvider.getResourceProvider(
        type,
        PropertyHelper.getPropertyIds(type),
        PropertyHelper.getKeyPropertyIds(type),
        managementController
      );
  }

  @Test
  public void testCreateRequestSchedule() throws Exception {
    AmbariManagementController managementController = createMock(AmbariManagementController.class);
    RequestStatusResponse response = createNiceMock(RequestStatusResponse.class);
    Clusters clusters = createNiceMock(Clusters.class);
    Cluster cluster = createNiceMock(Cluster.class);
    RequestExecutionFactory executionFactory = createNiceMock
      (RequestExecutionFactory.class);
    RequestExecution requestExecution = createNiceMock(RequestExecution.class);
    ExecutionScheduleManager executionScheduleManager = createNiceMock
      (ExecutionScheduleManager.class);

    expect(clusters.getCluster("Cluster100")).andReturn(cluster).anyTimes();
    expect(managementController.getClusters()).andReturn(clusters);
    expect(managementController.getExecutionScheduleManager()).andReturn
      (executionScheduleManager).anyTimes();
    expect(managementController.getRequestExecutionFactory()).andReturn
      (executionFactory);
    expect(managementController.getAuthName()).andReturn("admin").anyTimes();
    expect(managementController.getAuthId()).andReturn(1).anyTimes();

    Capture<Cluster> clusterCapture = new Capture<Cluster>();
    Capture<Batch> batchCapture = new Capture<Batch>();
    Capture<Schedule> scheduleCapture = new Capture<Schedule>();

    expect(executionFactory.createNew(capture(clusterCapture),
      capture(batchCapture), capture(scheduleCapture))).andReturn(requestExecution);

    replay(managementController, clusters, cluster, executionFactory,
      requestExecution, response, executionScheduleManager);

    RequestScheduleResourceProvider resourceProvider = getResourceProvider
      (managementController);

    Set<Map<String, Object>> propertySet = new LinkedHashSet<Map<String, Object>>();
    Map<String, Object> properties = new LinkedHashMap<String, Object>();

    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID, "Cluster100");
    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_DESC_PROPERTY_ID, "some description");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_DAY_OF_WEEK_PROPERTY_ID, "MON");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_MINUTES_PROPERTY_ID, "2");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_END_TIME_PROPERTY_ID, "2013-11-18T14:29:29-08:00");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_DAYS_OF_MONTH_PROPERTY_ID, "*");

    HashSet<Map<String, Object>> batch = new HashSet<Map<String, Object>>();
    Map<String, Object> batchSettings = new HashMap<String, Object>();
    batchSettings.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_BATCH_SEPARATION_PROPERTY_ID, "15");

    Map<String, Object> batchRequests = new HashMap<String, Object>();
    HashSet<Map<String, Object>> requestSet = new HashSet<Map<String, Object>>();

    Map<String, Object> request1 = new HashMap<String, Object>();
    Map<String, Object> request2 = new HashMap<String, Object>();

    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_TYPE_PROPERTY_ID, BatchRequest.Type.PUT.name());
    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_ORDER_ID_PROPERTY_ID, "20");
    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_URI_PROPERTY_ID, "SomeUpdateUri");
    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_BODY_PROPERTY_ID, "data1");

    request2.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_TYPE_PROPERTY_ID, BatchRequest.Type.DELETE.name());
    request2.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_ORDER_ID_PROPERTY_ID, "22");
    request2.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_URI_PROPERTY_ID, "SomeDeleteUri");

    requestSet.add(request1);
    requestSet.add(request2);

    batchRequests.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_BATCH_REQUESTS_PROPERTY_ID, requestSet);

    batch.add(batchSettings);
    batch.add(batchRequests);

    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_BATCH_PROPERTY_ID, batch);

    propertySet.add(properties);
    Request request = PropertyHelper.getCreateRequest(propertySet, null);
    resourceProvider.createResources(request);

    verify(managementController, clusters, cluster, executionFactory,
      requestExecution, response, executionScheduleManager);

    List<BatchRequest> testRequests = batchCapture.getValue().getBatchRequests();
    Assert.assertNotNull(testRequests);
    BatchRequest deleteReq = null;
    BatchRequest putReq = null;
    for (BatchRequest testBatchRequest : testRequests) {
      if (testBatchRequest.getType().equals(BatchRequest.Type.DELETE.name())) {
        deleteReq = testBatchRequest;
      } else {
        putReq = testBatchRequest;
      }
    }
    Assert.assertNotNull(deleteReq);
    Assert.assertNotNull(putReq);
    Assert.assertEquals("data1", putReq.getBody());
    Assert.assertNull(deleteReq.getBody());
  }

  @Test
  public void testUpdateRequestSchedule() throws Exception {
    AmbariManagementController managementController = createMock(AmbariManagementController.class);
    RequestStatusResponse response = createNiceMock(RequestStatusResponse.class);
    Clusters clusters = createNiceMock(Clusters.class);
    Cluster cluster = createNiceMock(Cluster.class);
    final RequestExecution requestExecution = createNiceMock(RequestExecution.class);
    RequestScheduleResponse requestScheduleResponse = createNiceMock
      (RequestScheduleResponse.class);
    ExecutionScheduleManager executionScheduleManager = createNiceMock
      (ExecutionScheduleManager.class);

    expect(managementController.getClusters()).andReturn(clusters).anyTimes();
    expect(clusters.getCluster("Cluster100")).andReturn(cluster).anyTimes();
    expect(managementController.getAuthName()).andReturn("admin").anyTimes();
    expect(managementController.getAuthId()).andReturn(1).anyTimes();
    expect(managementController.getExecutionScheduleManager()).andReturn
      (executionScheduleManager).anyTimes();

    expect(requestExecution.getId()).andReturn(25L).anyTimes();
    expect(requestExecution.convertToResponse()).andReturn
      (requestScheduleResponse).anyTimes();
    expect(requestExecution.convertToResponseWithBody()).andReturn
      (requestScheduleResponse).anyTimes();
    expect(requestScheduleResponse.getId()).andReturn(25L).anyTimes();
    expect(requestScheduleResponse.getClusterName()).andReturn("Cluster100")
      .anyTimes();

    expect(cluster.getAllRequestExecutions()).andStubAnswer(new IAnswer<Map<Long, RequestExecution>>() {
      @Override
      public Map<Long, RequestExecution> answer() throws Throwable {
        Map<Long, RequestExecution> requestExecutionMap = new HashMap<Long,
          RequestExecution>();
        requestExecutionMap.put(requestExecution.getId(), requestExecution);
        return requestExecutionMap;
      }
    });

    replay(managementController, clusters, cluster, requestExecution,
      response, requestScheduleResponse, executionScheduleManager);

    RequestScheduleResourceProvider resourceProvider = getResourceProvider
      (managementController);

    Map<String, Object> properties = new LinkedHashMap<String, Object>();

    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID, "Cluster100");
    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_DESC_PROPERTY_ID, "some description");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_DAY_OF_WEEK_PROPERTY_ID, "MON");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_MINUTES_PROPERTY_ID, "2");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_END_TIME_PROPERTY_ID, "2013-11-18T14:29:29-08:00");
    properties.put(RequestScheduleResourceProvider
      .SCHEDULE_DAYS_OF_MONTH_PROPERTY_ID, "*");

    HashSet<Map<String, Object>> batch = new HashSet<Map<String, Object>>();
    Map<String, Object> batchSettings = new HashMap<String, Object>();
    batchSettings.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_BATCH_SEPARATION_PROPERTY_ID, "15");

    Map<String, Object> batchRequests = new HashMap<String, Object>();
    HashSet<Map<String, Object>> requestSet = new HashSet<Map<String, Object>>();

    Map<String, Object> request1 = new HashMap<String, Object>();
    Map<String, Object> request2 = new HashMap<String, Object>();

    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_TYPE_PROPERTY_ID, BatchRequest.Type.PUT.name());
    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_ORDER_ID_PROPERTY_ID, "20");
    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_URI_PROPERTY_ID, "SomeUpdateUri");
    request1.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_BODY_PROPERTY_ID, "data1");

    request2.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_TYPE_PROPERTY_ID, BatchRequest.Type.DELETE.name());
    request2.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_ORDER_ID_PROPERTY_ID, "22");
    request2.put(RequestScheduleResourceProvider
      .BATCH_REQUEST_URI_PROPERTY_ID, "SomeDeleteUri");

    requestSet.add(request1);
    requestSet.add(request2);

    batchRequests.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_BATCH_REQUESTS_PROPERTY_ID, requestSet);

    batch.add(batchSettings);
    batch.add(batchRequests);

    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_BATCH_PROPERTY_ID, batch);

    Map<String, String> mapRequestProps = new HashMap<String, String>();
    mapRequestProps.put("context", "Called from a test");

    Request request = PropertyHelper.getUpdateRequest(properties, mapRequestProps);
    Predicate predicate = new PredicateBuilder().property
      (RequestScheduleResourceProvider.REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID)
      .equals("Cluster100").and().property(RequestScheduleResourceProvider
        .REQUEST_SCHEDULE_ID_PROPERTY_ID).equals(25L).toPredicate();

    resourceProvider.updateResources(request, predicate);

    verify(managementController, clusters, cluster, requestExecution,
      response, requestScheduleResponse, executionScheduleManager);
  }

  @Test
  public void testGetRequestSchedule() throws Exception {
    AmbariManagementController managementController = createMock(AmbariManagementController.class);
    RequestStatusResponse response = createNiceMock(RequestStatusResponse.class);
    Clusters clusters = createNiceMock(Clusters.class);
    Cluster cluster = createNiceMock(Cluster.class);
    final RequestExecution requestExecution = createNiceMock(RequestExecution.class);
    RequestScheduleResponse requestScheduleResponse = createNiceMock
      (RequestScheduleResponse.class);

    expect(managementController.getClusters()).andReturn(clusters).anyTimes();
    expect(clusters.getCluster("Cluster100")).andReturn(cluster).anyTimes();
    expect(managementController.getAuthName()).andReturn("admin").anyTimes();

    expect(requestExecution.getId()).andReturn(25L).anyTimes();
    expect(requestExecution.getStatus()).andReturn(RequestExecution.Status
      .SCHEDULED.name()).anyTimes();
    expect(requestExecution.convertToResponse()).andReturn
      (requestScheduleResponse).anyTimes();
    expect(requestExecution.convertToResponseWithBody()).andReturn
      (requestScheduleResponse).anyTimes();
    expect(requestScheduleResponse.getId()).andReturn(25L).anyTimes();
    expect(requestScheduleResponse.getClusterName()).andReturn("Cluster100")
      .anyTimes();

    expect(cluster.getAllRequestExecutions()).andStubAnswer(new IAnswer<Map<Long, RequestExecution>>() {
      @Override
      public Map<Long, RequestExecution> answer() throws Throwable {
        Map<Long, RequestExecution> requestExecutionMap = new HashMap<Long,
          RequestExecution>();
        requestExecutionMap.put(requestExecution.getId(), requestExecution);
        return requestExecutionMap;
      }
    });

    replay(managementController, clusters, cluster, requestExecution,
      response, requestScheduleResponse);

    RequestScheduleResourceProvider resourceProvider = getResourceProvider
      (managementController);

    Map<String, Object> properties = new LinkedHashMap<String, Object>();

    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID, "Cluster100");
    properties.put(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_DESC_PROPERTY_ID, "some description");

    Set<String> propertyIds = new HashSet<String>();
    propertyIds.add(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID);
    propertyIds.add(RequestScheduleResourceProvider
      .REQUEST_SCHEDULE_ID_PROPERTY_ID);

    Request request = PropertyHelper.getReadRequest(propertyIds);

    // Read by id
    Predicate predicate = new PredicateBuilder().property
      (RequestScheduleResourceProvider.REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID)
      .equals("Cluster100").and().property(RequestScheduleResourceProvider
        .REQUEST_SCHEDULE_ID_PROPERTY_ID).equals(25L).toPredicate();

    Set<Resource> resources = resourceProvider.getResources(request,
      predicate);

    Assert.assertEquals(1, resources.size());
    Assert.assertEquals(25L, resources.iterator().next().getPropertyValue
      (RequestScheduleResourceProvider.REQUEST_SCHEDULE_ID_PROPERTY_ID));

    // Read all
    predicate = new PredicateBuilder().property
      (RequestScheduleResourceProvider.REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID)
      .equals("Cluster100").toPredicate();

    resources = resourceProvider.getResources(request, predicate);

    Assert.assertEquals(1, resources.size());
    Assert.assertEquals(25L, resources.iterator().next().getPropertyValue
      (RequestScheduleResourceProvider.REQUEST_SCHEDULE_ID_PROPERTY_ID));

    verify(managementController, clusters, cluster, requestExecution,
      response, requestScheduleResponse);
  }

  @Test
  public void testDeleteRequestSchedule() throws Exception {
    AmbariManagementController managementController = createMock(AmbariManagementController.class);
    Clusters clusters = createNiceMock(Clusters.class);
    Cluster cluster = createNiceMock(Cluster.class);
    RequestExecution requestExecution = createNiceMock(RequestExecution.class);
    ExecutionScheduleManager executionScheduleManager = createNiceMock
      (ExecutionScheduleManager.class);

    Map<Long, RequestExecution> requestExecutionMap = new HashMap<Long,
      RequestExecution>();
    requestExecutionMap.put(1L, requestExecution);

    expect(managementController.getAuthName()).andReturn("admin").anyTimes();
    expect(managementController.getClusters()).andReturn(clusters).anyTimes();
    expect(managementController.getExecutionScheduleManager()).andReturn
      (executionScheduleManager).anyTimes();
    expect(clusters.getCluster("Cluster100")).andReturn(cluster).anyTimes();
    expect(cluster.getAllRequestExecutions()).andReturn(requestExecutionMap);

    replay(managementController, clusters, cluster, executionScheduleManager,
      requestExecution );

    RequestScheduleResourceProvider resourceProvider = getResourceProvider
      (managementController);

    AbstractResourceProviderTest.TestObserver observer = new AbstractResourceProviderTest.TestObserver();

    ((ObservableResourceProvider) resourceProvider).addObserver(observer);

    Predicate predicate = new PredicateBuilder().property
      (RequestScheduleResourceProvider.REQUEST_SCHEDULE_CLUSTER_NAME_PROPERTY_ID)
      .equals("Cluster100").and().property(RequestScheduleResourceProvider
        .REQUEST_SCHEDULE_ID_PROPERTY_ID).equals(1L).toPredicate();

    resourceProvider.deleteResources(new RequestImpl(null, null, null, null), predicate);

    ResourceProviderEvent lastEvent = observer.getLastEvent();
    Assert.assertNotNull(lastEvent);
    Assert.assertEquals(Resource.Type.RequestSchedule, lastEvent.getResourceType());
    Assert.assertEquals(ResourceProviderEvent.Type.Delete, lastEvent.getType());
    Assert.assertEquals(predicate, lastEvent.getPredicate());
    Assert.assertNull(lastEvent.getRequest());

    verify(managementController, clusters, cluster, executionScheduleManager,
      requestExecution);
  }
}
