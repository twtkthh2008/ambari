/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ambari.server.topology;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.isNull;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.controller.ClusterRequest;
import org.apache.ambari.server.controller.ConfigurationRequest;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.controller.ShortTaskStatus;
import org.apache.ambari.server.controller.internal.HostResourceProvider;
import org.apache.ambari.server.controller.internal.ProvisionClusterRequest;
import org.apache.ambari.server.controller.internal.ScaleClusterRequest;
import org.apache.ambari.server.controller.internal.Stack;
import org.apache.ambari.server.controller.spi.ClusterController;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.events.RequestFinishedEvent;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.stack.NoSuchStackException;
import org.apache.ambari.server.state.SecurityType;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.EasyMockRule;
import org.easymock.EasyMockSupport;
import org.easymock.Mock;
import org.easymock.MockType;
import org.easymock.TestSubject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import junit.framework.Assert;

/**
 * TopologyManager unit tests
 */
public class TopologyManagerTest {

  private static final String CLUSTER_NAME = "test-cluster";
  private static final long CLUSTER_ID = 1;
  private static final String BLUEPRINT_NAME = "test-bp";
  private static final String STACK_NAME = "test-stack";
  private static final String STACK_VERSION = "test-stack-version";

  @Rule
  public EasyMockRule mocks = new EasyMockRule(this);

  @TestSubject
  private TopologyManager topologyManager = new TopologyManager();

  @TestSubject
  private TopologyManager topologyManagerReplay = new TopologyManager();

  @Mock(type = MockType.NICE)
  private Blueprint blueprint;

  @Mock(type = MockType.NICE)
  private Stack stack;

  @Mock(type = MockType.NICE)
  private ProvisionClusterRequest request;
  private final PersistedTopologyRequest persistedTopologyRequest = new PersistedTopologyRequest(1, request);
  @Mock(type = MockType.STRICT)
  private LogicalRequestFactory logicalRequestFactory;
  @Mock(type = MockType.DEFAULT)
  private LogicalRequest logicalRequest;
  @Mock(type = MockType.NICE)
  private AmbariContext ambariContext;
  @Mock(type = MockType.NICE)
  private ConfigurationRequest configurationRequest;
  @Mock(type = MockType.NICE)
  private ConfigurationRequest configurationRequest2;
  @Mock(type = MockType.NICE)
  private ConfigurationRequest configurationRequest3;
  @Mock(type = MockType.NICE)
  private RequestStatusResponse requestStatusResponse;
  @Mock(type = MockType.STRICT)
  private ExecutorService executor;
  @Mock(type = MockType.NICE)
  private PersistedState persistedState;
  @Mock(type = MockType.NICE)
  private HostGroup group1;
  @Mock(type = MockType.NICE)
  private HostGroup group2;
  @Mock(type = MockType.STRICT)
  private SecurityConfigurationFactory securityConfigurationFactory;
  @Mock(type = MockType.STRICT)
  private CredentialStoreService credentialStoreService;
  @Mock(type = MockType.STRICT)
  private ClusterController clusterController;
  @Mock(type = MockType.STRICT)
  private ResourceProvider resourceProvider;

  @Mock(type = MockType.STRICT)
  private Future mockFuture;

  private final Configuration stackConfig = new Configuration(new HashMap<String, Map<String, String>>(),
      new HashMap<String, Map<String, Map<String, String>>>());
  private final Configuration bpConfiguration = new Configuration(new HashMap<String, Map<String, String>>(),
      new HashMap<String, Map<String, Map<String, String>>>(), stackConfig);
  private final Configuration topoConfiguration = new Configuration(new HashMap<String, Map<String, String>>(),
      new HashMap<String, Map<String, Map<String, String>>>(), bpConfiguration);
  private final Configuration bpGroup1Config = new Configuration(new HashMap<String, Map<String, String>>(),
      new HashMap<String, Map<String, Map<String, String>>>(), bpConfiguration);
  private final Configuration bpGroup2Config = new Configuration(new HashMap<String, Map<String, String>>(),
      new HashMap<String, Map<String, Map<String, String>>>(), bpConfiguration);
  //todo: topo config hierarchy is wrong: bpGroupConfigs should extend topo cluster config
  private final Configuration topoGroup1Config = new Configuration(new HashMap<String, Map<String, String>>(),
      new HashMap<String, Map<String, Map<String, String>>>(), bpGroup1Config);
  private final Configuration topoGroup2Config = new Configuration(new HashMap<String, Map<String, String>>(),
      new HashMap<String, Map<String, Map<String, String>>>(), bpGroup2Config);

  private HostGroupInfo group1Info = new HostGroupInfo("group1");
  private HostGroupInfo group2Info = new HostGroupInfo("group2");
  private Map<String, HostGroupInfo> groupInfoMap = new HashMap<String, HostGroupInfo>();

  private Collection<Component> group1Components = Arrays.asList(new Component("component1"), new Component("component2"), new Component("component3"));
  private Collection<Component> group2Components = Arrays.asList(new Component("component3"), new Component("component4"));

  private Map<String, Collection<String>> group1ServiceComponents = new HashMap<String, Collection<String>>();
  private Map<String, Collection<String>> group2ServiceComponents = new HashMap<String, Collection<String>>();

  private Map<String, Collection<String>> serviceComponents = new HashMap<String, Collection<String>>();

  private String predicate = "Hosts/host_name=foo";

  private List<TopologyValidator> topologyValidators = new ArrayList<TopologyValidator>();

  private Capture<ClusterTopology> clusterTopologyCapture;
  private Capture<Map<String, Object>> configRequestPropertiesCapture;
  private Capture<Map<String, Object>> configRequestPropertiesCapture2;
  private Capture<Map<String, Object>> configRequestPropertiesCapture3;
  private Capture<ClusterRequest> updateClusterConfigRequestCapture;
  private Capture<Runnable> updateConfigTaskCapture;

  @Before
  public void setup() throws Exception {
    clusterTopologyCapture = newCapture();
    configRequestPropertiesCapture = newCapture();
    configRequestPropertiesCapture2 = newCapture();
    configRequestPropertiesCapture3 = newCapture();
    updateClusterConfigRequestCapture = newCapture();
    updateConfigTaskCapture = newCapture();

    topoConfiguration.setProperty("service1-site", "s1-prop", "s1-prop-value");
    topoConfiguration.setProperty("service2-site", "s2-prop", "s2-prop-value");
    topoConfiguration.setProperty("cluster-env", "g-prop", "g-prop-value");

    //clusterRequestCapture = new Capture<ClusterRequest>();
    // group 1 has fqdn specified
    group1Info.addHost("host1");
    group1Info.setConfiguration(topoGroup1Config);
    // group 2 has host_count and host_predicate specified
    group2Info.setRequestedCount(2);
    group2Info.setPredicate(predicate);
    group2Info.setConfiguration(topoGroup2Config);

    groupInfoMap.put("group1", group1Info);
    groupInfoMap.put("group2", group2Info);

    Map<String, HostGroup> groupMap = new HashMap<String, HostGroup>();
    groupMap.put("group1", group1);
    groupMap.put("group2", group2);

    serviceComponents.put("service1", Arrays.asList("component1", "component3"));
    serviceComponents.put("service2", Arrays.asList("component2", "component4"));

    group1ServiceComponents.put("service1", Arrays.asList("component1", "component3"));
    group1ServiceComponents.put("service2", Collections.singleton("component2"));
    group2ServiceComponents.put("service2", Collections.singleton("component3"));
    group2ServiceComponents.put("service2", Collections.singleton("component4"));

    expect(blueprint.getHostGroup("group1")).andReturn(group1).anyTimes();
    expect(blueprint.getHostGroup("group2")).andReturn(group2).anyTimes();
    expect(blueprint.getComponents("service1")).andReturn(Arrays.asList("component1", "component3")).anyTimes();
    expect(blueprint.getComponents("service2")).andReturn(Arrays.asList("component2", "component4")).anyTimes();
    expect(blueprint.getConfiguration()).andReturn(bpConfiguration).anyTimes();
    expect(blueprint.getHostGroups()).andReturn(groupMap).anyTimes();
    expect(blueprint.getHostGroupsForComponent("component1")).andReturn(Collections.singleton(group1)).anyTimes();
    expect(blueprint.getHostGroupsForComponent("component2")).andReturn(Collections.singleton(group1)).anyTimes();
    expect(blueprint.getHostGroupsForComponent("component3")).andReturn(Arrays.asList(group1, group2)).anyTimes();
    expect(blueprint.getHostGroupsForComponent("component4")).andReturn(Collections.singleton(group2)).anyTimes();
    expect(blueprint.getHostGroupsForService("service1")).andReturn(Arrays.asList(group1, group2)).anyTimes();
    expect(blueprint.getHostGroupsForService("service2")).andReturn(Arrays.asList(group1, group2)).anyTimes();
    expect(blueprint.getName()).andReturn(BLUEPRINT_NAME).anyTimes();
    expect(blueprint.getServices()).andReturn(Arrays.asList("service1", "service2")).anyTimes();
    expect(blueprint.getStack()).andReturn(stack).anyTimes();
    // don't expect toEntity()

    expect(stack.getAllConfigurationTypes("service1")).andReturn(Arrays.asList("service1-site", "service1-env")).anyTimes();
    expect(stack.getAllConfigurationTypes("service2")).andReturn(Arrays.asList("service2-site", "service2-env")).anyTimes();
    expect(stack.getAutoDeployInfo("component1")).andReturn(null).anyTimes();
    expect(stack.getAutoDeployInfo("component2")).andReturn(null).anyTimes();
    expect(stack.getAutoDeployInfo("component3")).andReturn(null).anyTimes();
    expect(stack.getAutoDeployInfo("component4")).andReturn(null).anyTimes();
    expect(stack.getCardinality("component1")).andReturn(new Cardinality("1")).anyTimes();
    expect(stack.getCardinality("component2")).andReturn(new Cardinality("1")).anyTimes();
    expect(stack.getCardinality("component3")).andReturn(new Cardinality("1+")).anyTimes();
    expect(stack.getCardinality("component4")).andReturn(new Cardinality("1+")).anyTimes();
    expect(stack.getComponents()).andReturn(serviceComponents).anyTimes();
    expect(stack.getComponents("service1")).andReturn(serviceComponents.get("service1")).anyTimes();
    expect(stack.getComponents("service2")).andReturn(serviceComponents.get("service2")).anyTimes();
    expect(stack.getServiceForConfigType("service1-site")).andReturn("service1").anyTimes();
    expect(stack.getServiceForConfigType("service2-site")).andReturn("service2").anyTimes();
    expect(stack.getConfiguration()).andReturn(stackConfig).anyTimes();
    expect(stack.getName()).andReturn(STACK_NAME).anyTimes();
    expect(stack.getVersion()).andReturn(STACK_VERSION).anyTimes();
    expect(stack.getExcludedConfigurationTypes("service1")).andReturn(Collections.<String>emptySet()).anyTimes();
    expect(stack.getExcludedConfigurationTypes("service2")).andReturn(Collections.<String>emptySet()).anyTimes();

    expect(request.getBlueprint()).andReturn(blueprint).anyTimes();
    expect(request.getClusterId()).andReturn(CLUSTER_ID).anyTimes();
    expect(request.getClusterName()).andReturn(CLUSTER_NAME).anyTimes();
    expect(request.getDescription()).andReturn("Provision Cluster Test").anyTimes();
    expect(request.getConfiguration()).andReturn(topoConfiguration).anyTimes();
    expect(request.getHostGroupInfo()).andReturn(groupInfoMap).anyTimes();
    expect(request.getTopologyValidators()).andReturn(topologyValidators).anyTimes();

    expect(request.getConfigRecommendationStrategy()).andReturn(ConfigRecommendationStrategy.NEVER_APPLY).anyTimes();

    expect(request.getSecurityConfiguration()).andReturn(null).anyTimes();


    expect(group1.getBlueprintName()).andReturn(BLUEPRINT_NAME).anyTimes();
    expect(group1.getCardinality()).andReturn("test cardinality").anyTimes();
    expect(group1.containsMasterComponent()).andReturn(true).anyTimes();
    expect(group1.getComponents()).andReturn(group1Components).anyTimes();
    expect(group1.getComponents("service1")).andReturn(group1ServiceComponents.get("service1")).anyTimes();
    expect(group1.getComponents("service2")).andReturn(group1ServiceComponents.get("service1")).anyTimes();
    expect(group1.getConfiguration()).andReturn(topoGroup1Config).anyTimes();
    expect(group1.getName()).andReturn("group1").anyTimes();
    expect(group1.getServices()).andReturn(Arrays.asList("service1", "service2")).anyTimes();
    expect(group1.getStack()).andReturn(stack).anyTimes();

    expect(group2.getBlueprintName()).andReturn(BLUEPRINT_NAME).anyTimes();
    expect(group2.getCardinality()).andReturn("test cardinality").anyTimes();
    expect(group2.containsMasterComponent()).andReturn(false).anyTimes();
    expect(group2.getComponents()).andReturn(group2Components).anyTimes();
    expect(group2.getComponents("service1")).andReturn(group2ServiceComponents.get("service1")).anyTimes();
    expect(group2.getComponents("service2")).andReturn(group2ServiceComponents.get("service2")).anyTimes();
    expect(group2.getConfiguration()).andReturn(topoGroup2Config).anyTimes();
    expect(group2.getName()).andReturn("group2").anyTimes();
    expect(group2.getServices()).andReturn(Arrays.asList("service1", "service2")).anyTimes();
    expect(group2.getStack()).andReturn(stack).anyTimes();


    expect(logicalRequestFactory.createRequest(eq(1L), (TopologyRequest) anyObject(), capture(clusterTopologyCapture))).
        andReturn(logicalRequest).anyTimes();
    expect(logicalRequest.getRequestId()).andReturn(1L).anyTimes();
    expect(logicalRequest.getClusterId()).andReturn(CLUSTER_ID).anyTimes();
    expect(logicalRequest.getReservedHosts()).andReturn(Collections.singleton("host1")).anyTimes();
    expect(logicalRequest.getRequestStatus()).andReturn(requestStatusResponse).anyTimes();

    expect(ambariContext.getPersistedTopologyState()).andReturn(persistedState).anyTimes();
    //todo: don't ignore param
    ambariContext.createAmbariResources(isA(ClusterTopology.class), eq(CLUSTER_NAME), (SecurityType) isNull(), (String) isNull());
    expectLastCall().anyTimes();
    expect(ambariContext.getNextRequestId()).andReturn(1L).anyTimes();
    expect(ambariContext.isClusterKerberosEnabled(CLUSTER_ID)).andReturn(false).anyTimes();
    expect(ambariContext.getClusterId(CLUSTER_NAME)).andReturn(CLUSTER_ID).anyTimes();
    expect(ambariContext.getClusterName(CLUSTER_ID)).andReturn(CLUSTER_NAME).anyTimes();
    // cluster configuration task run() isn't executed by mock executor
    // so only INITIAL config
    expect(ambariContext.createConfigurationRequests(capture(configRequestPropertiesCapture))).
        andReturn(Collections.singletonList(configurationRequest)).anyTimes();
    expect(ambariContext.createConfigurationRequests(capture(configRequestPropertiesCapture2))).
        andReturn(Collections.singletonList(configurationRequest2)).anyTimes();
    expect(ambariContext.createConfigurationRequests(capture(configRequestPropertiesCapture3))).
        andReturn(Collections.singletonList(configurationRequest3)).anyTimes();

    ambariContext.setConfigurationOnCluster(capture(updateClusterConfigRequestCapture));
    expectLastCall().anyTimes();
    ambariContext.persistInstallStateForUI(CLUSTER_NAME, STACK_NAME, STACK_VERSION);
    expectLastCall().anyTimes();

    expect(clusterController.ensureResourceProvider(anyObject(Resource.Type.class))).andReturn(resourceProvider);

    expect(executor.submit(anyObject(AsyncCallableService.class))).andReturn(mockFuture);

    expectLastCall().anyTimes();

    expect(persistedState.persistTopologyRequest(request)).andReturn(persistedTopologyRequest).anyTimes();
    persistedState.persistLogicalRequest(logicalRequest, 1);
    expectLastCall().anyTimes();


    Class clazz = TopologyManager.class;

    Field f = clazz.getDeclaredField("executor");
    f.setAccessible(true);
    f.set(topologyManager, executor);

    EasyMockSupport.injectMocks(topologyManager);

    Field f2 = clazz.getDeclaredField("executor");
    f2.setAccessible(true);
    f2.set(topologyManagerReplay, executor);

    EasyMockSupport.injectMocks(topologyManagerReplay);

  }

  @After
  public void tearDown() {
    verify(blueprint, stack, request, group1, group2, ambariContext, logicalRequestFactory,
        logicalRequest, configurationRequest, configurationRequest2, configurationRequest3,
        requestStatusResponse, executor, persistedState, mockFuture);

    reset(blueprint, stack, request, group1, group2, ambariContext, logicalRequestFactory,
        logicalRequest, configurationRequest, configurationRequest2, configurationRequest3,
        requestStatusResponse, executor, persistedState, mockFuture);
  }

  @Test
  public void testProvisionCluster() throws Exception {
    expect(persistedState.getAllRequests()).andReturn(Collections.<ClusterTopology,
            List<LogicalRequest>>emptyMap()).anyTimes();
    replayAll();

    topologyManager.provisionCluster(request);
    //todo: assertions
  }

  @Test
  public void testBlueprintRequestCompletion() throws Exception {
    List<ShortTaskStatus> tasks = new ArrayList<>();
    ShortTaskStatus t1 = new ShortTaskStatus();
    t1.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t1);
    ShortTaskStatus t2 = new ShortTaskStatus();
    t2.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t2);
    ShortTaskStatus t3 = new ShortTaskStatus();
    t3.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t3);

    expect(requestStatusResponse.getTasks()).andReturn(tasks).anyTimes();
    expect(persistedState.getAllRequests()).andReturn(Collections.<ClusterTopology,
            List<LogicalRequest>>emptyMap()).anyTimes();
    expect(persistedState.getProvisionRequest(CLUSTER_ID)).andReturn(logicalRequest).anyTimes();
    replayAll();
    topologyManager.provisionCluster(request);
    requestFinished();
    Assert.assertTrue(topologyManager.isClusterProvisionWithBlueprintFinished(CLUSTER_ID));
  }

  @Test
  public void testBlueprintRequestCompletion__Failure() throws Exception {
    List<ShortTaskStatus> tasks = new ArrayList<>();
    ShortTaskStatus t1 = new ShortTaskStatus();
    t1.setStatus(HostRoleStatus.FAILED.toString());
    tasks.add(t1);
    ShortTaskStatus t2 = new ShortTaskStatus();
    t2.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t2);
    ShortTaskStatus t3 = new ShortTaskStatus();
    t3.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t3);

    expect(requestStatusResponse.getTasks()).andReturn(tasks).anyTimes();
    expect(persistedState.getAllRequests()).andReturn(Collections.<ClusterTopology,
            List<LogicalRequest>>emptyMap()).anyTimes();
    expect(persistedState.getProvisionRequest(CLUSTER_ID)).andReturn(logicalRequest).anyTimes();
    replayAll();
    topologyManager.provisionCluster(request);
    requestFinished();
    Assert.assertTrue(topologyManager.isClusterProvisionWithBlueprintFinished(CLUSTER_ID));
  }

  @Test
  public void testBlueprintRequestCompletion__InProgress() throws Exception {
    List<ShortTaskStatus> tasks = new ArrayList<>();
    ShortTaskStatus t1 = new ShortTaskStatus();
    t1.setStatus(HostRoleStatus.IN_PROGRESS.toString());
    tasks.add(t1);
    ShortTaskStatus t2 = new ShortTaskStatus();
    t2.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t2);
    ShortTaskStatus t3 = new ShortTaskStatus();
    t3.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t3);

    expect(requestStatusResponse.getTasks()).andReturn(tasks).anyTimes();
    expect(persistedState.getAllRequests()).andReturn(Collections.<ClusterTopology,
            List<LogicalRequest>>emptyMap()).anyTimes();
    expect(persistedState.getProvisionRequest(CLUSTER_ID)).andReturn(logicalRequest).anyTimes();
    replayAll();
    topologyManager.provisionCluster(request);
    requestFinished();
    Assert.assertFalse(topologyManager.isClusterProvisionWithBlueprintFinished(CLUSTER_ID));
  }

  @Test
  public void testBlueprintRequestCompletion__NoRequest() throws Exception {
    TopologyManager tm = new TopologyManager();
    tm.onRequestFinished(new RequestFinishedEvent(CLUSTER_ID, 1));
    Assert.assertFalse(tm.isClusterProvisionWithBlueprintTracked(CLUSTER_ID));
    replayAll();
  }

  @Test
  public void testBlueprintRequestCompletion__Replay() throws Exception {
    List<ShortTaskStatus> tasks = new ArrayList<>();
    ShortTaskStatus t1 = new ShortTaskStatus();
    t1.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t1);
    ShortTaskStatus t2 = new ShortTaskStatus();
    t2.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t2);
    ShortTaskStatus t3 = new ShortTaskStatus();
    t3.setStatus(HostRoleStatus.COMPLETED.toString());
    tasks.add(t3);

    Map<ClusterTopology,List<LogicalRequest>> allRequests = new HashMap<>();
    List<LogicalRequest> logicalRequests = new ArrayList<>();
    logicalRequests.add(logicalRequest);
    ClusterTopology clusterTopologyMock = EasyMock.createNiceMock(ClusterTopology.class);
    expect(clusterTopologyMock.getClusterId()).andReturn(CLUSTER_ID).anyTimes();

    expect(ambariContext.isTopologyResolved(EasyMock.anyLong())).andReturn(true).anyTimes();

    allRequests.put(clusterTopologyMock, logicalRequests);
    expect(persistedState.getAllRequests()).andReturn(allRequests).anyTimes();
    expect(persistedState.getProvisionRequest(CLUSTER_ID)).andReturn(logicalRequest).anyTimes();
    expect(logicalRequest.hasCompleted()).andReturn(true).anyTimes();
    expect(requestStatusResponse.getTasks()).andReturn(tasks).anyTimes();
    replayAll();
    EasyMock.replay(clusterTopologyMock);
    topologyManagerReplay.getRequest(1L); // calling ensureInitialized indirectly
    Assert.assertTrue(topologyManagerReplay.isClusterProvisionWithBlueprintFinished(CLUSTER_ID));
  }

  private void requestFinished() {
    topologyManager.onRequestFinished(new RequestFinishedEvent(CLUSTER_ID, 1));
  }

  private void replayAll() {
    replay(blueprint, stack, request, group1, group2, ambariContext, logicalRequestFactory,
            configurationRequest, configurationRequest2, configurationRequest3, executor,
            persistedState, securityConfigurationFactory, credentialStoreService, clusterController, resourceProvider,
            mockFuture, requestStatusResponse, logicalRequest);
  }

  @Test(expected = InvalidTopologyException.class)
  public void testScaleHosts__alreadyExistingHost() throws InvalidTopologyTemplateException, InvalidTopologyException, AmbariException, NoSuchStackException {
    HashSet<Map<String, Object>> propertySet = new HashSet<>();
    Map<String,Object> properties = new TreeMap<>();
    properties.put(HostResourceProvider.HOST_NAME_PROPERTY_ID, "host1");
    properties.put(HostResourceProvider.HOSTGROUP_PROPERTY_ID, "group1");
    properties.put(HostResourceProvider.HOST_CLUSTER_NAME_PROPERTY_ID, CLUSTER_NAME);
    properties.put(HostResourceProvider.BLUEPRINT_PROPERTY_ID, BLUEPRINT_NAME);
    propertySet.add(properties);
    BlueprintFactory bpfMock = EasyMock.createNiceMock(BlueprintFactory.class);
    EasyMock.expect(bpfMock.getBlueprint(BLUEPRINT_NAME)).andReturn(blueprint).anyTimes();
    ScaleClusterRequest.init(bpfMock);
    replay(bpfMock);
    expect(persistedState.getAllRequests()).andReturn(Collections.<ClusterTopology,
      List<LogicalRequest>>emptyMap()).anyTimes();
    replayAll();
    topologyManager.provisionCluster(request);
    topologyManager.scaleHosts(new ScaleClusterRequest(propertySet));
    Assert.fail("InvalidTopologyException should have been thrown");
  }
}
