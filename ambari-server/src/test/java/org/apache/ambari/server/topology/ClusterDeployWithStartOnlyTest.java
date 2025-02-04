/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.topology;

import static org.easymock.EasyMock.anyBoolean;
import static org.easymock.EasyMock.anyLong;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createMockBuilder;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.isNull;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.ambari.server.Role;
import org.apache.ambari.server.RoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.AmbariServer;
import org.apache.ambari.server.controller.ClusterRequest;
import org.apache.ambari.server.controller.ConfigurationRequest;
import org.apache.ambari.server.controller.RequestStatusResponse;
import org.apache.ambari.server.controller.internal.ProvisionAction;
import org.apache.ambari.server.controller.internal.ProvisionClusterRequest;
import org.apache.ambari.server.controller.internal.Stack;
import org.apache.ambari.server.controller.spi.ClusterController;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.orm.entities.TopologyLogicalRequestEntity;
import org.apache.ambari.server.security.encryption.CredentialStoreService;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.ComponentInfo;
import org.apache.ambari.server.state.SecurityType;
import org.easymock.Capture;
import org.easymock.EasyMockRule;
import org.easymock.EasyMockSupport;
import org.easymock.Mock;
import org.easymock.MockType;
import org.easymock.TestSubject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest(AmbariServer.class)
public class ClusterDeployWithStartOnlyTest {
  private static final String CLUSTER_NAME = "test-cluster";
  private static final long CLUSTER_ID = 1;
  private static final String BLUEPRINT_NAME = "test-bp";
  private static final String STACK_NAME = "test-stack";
  private static final String STACK_VERSION = "test-stack-version";

  @Rule
  public EasyMockRule mocks = new EasyMockRule(this);

  @TestSubject
  private TopologyManager topologyManager = new TopologyManager();

  @Mock(type = MockType.NICE)
  private Blueprint blueprint;

  @Mock(type = MockType.NICE)
  private Stack stack;

  @Mock(type = MockType.NICE)
  private ProvisionClusterRequest request;
  private PersistedTopologyRequest persistedTopologyRequest;
//  @Mock(type = MockType.STRICT)
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
  @Mock(type = MockType.STRICT)
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
  @Mock(type = MockType.NICE)
  private AmbariManagementController managementController;
  @Mock(type = MockType.NICE)
  private Clusters clusters;
  @Mock(type = MockType.NICE)
  private Cluster cluster;
  @Mock(type = MockType.NICE)
  private HostRoleCommand hostRoleCommandInstallComponent3;
  @Mock(type = MockType.NICE)
  private HostRoleCommand hostRoleCommandInstallComponent4;
  @Mock(type = MockType.NICE)
  private HostRoleCommand hostRoleCommandStartComponent1;
  @Mock(type = MockType.NICE)
  private HostRoleCommand hostRoleCommandStartComponent2;

  @Mock(type = MockType.NICE)
  private ComponentInfo serviceComponentInfo;
  @Mock(type = MockType.NICE)
  private ComponentInfo clientComponentInfo;

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

  private final Configuration topoGroup1Config = new Configuration(new HashMap<String, Map<String, String>>(),
    new HashMap<String, Map<String, Map<String, String>>>(), bpGroup1Config);
  private final Configuration topoGroup2Config = new Configuration(new HashMap<String, Map<String, String>>(),
    new HashMap<String, Map<String, Map<String, String>>>(), bpGroup2Config);

  private HostGroupInfo group1Info = new HostGroupInfo("group1");
  private HostGroupInfo group2Info = new HostGroupInfo("group2");
  private Map<String, HostGroupInfo> groupInfoMap = new HashMap<String, HostGroupInfo>();

  private Collection<String> group1Components = Arrays.asList("component1", "component2", "component3");
  private Collection<String> group2Components = Arrays.asList("component3", "component4");

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

    expect(serviceComponentInfo.isClient()).andReturn(false).anyTimes();
    expect(clientComponentInfo.isClient()).andReturn(true).anyTimes();

    expect(stack.getComponentInfo("component1")).andReturn(serviceComponentInfo).anyTimes();
    expect(stack.getComponentInfo("component2")).andReturn(serviceComponentInfo).anyTimes();
    expect(stack.getComponentInfo("component3")).andReturn(clientComponentInfo).anyTimes();
    expect(stack.getComponentInfo("component4")).andReturn(clientComponentInfo).anyTimes();

    expect(stack.getCardinality("component1")).andReturn(new Cardinality("1")).anyTimes();
    expect(stack.getCardinality("component2")).andReturn(new Cardinality("1")).anyTimes();
    expect(stack.getCardinality("component3")).andReturn(new Cardinality("1+")).anyTimes();
    expect(stack.getCardinality("component4")).andReturn(new Cardinality("1+")).anyTimes();
    expect(stack.getComponents()).andReturn(serviceComponents).anyTimes();
    expect(stack.getComponents("service1")).andReturn(serviceComponents.get("service1")).anyTimes();
    expect(stack.getComponents("service2")).andReturn(serviceComponents.get("service2")).anyTimes();
    expect(stack.getConfiguration()).andReturn(stackConfig).anyTimes();
    expect(stack.getName()).andReturn(STACK_NAME).anyTimes();
    expect(stack.getVersion()).andReturn(STACK_VERSION).anyTimes();
    expect(stack.getServiceForConfigType("service1-site")).andReturn("service1").anyTimes();
    expect(stack.getServiceForConfigType("service2-site")).andReturn("service2").anyTimes();
    expect(stack.getExcludedConfigurationTypes("service1")).andReturn(Collections.<String>emptySet()).anyTimes();
    expect(stack.getExcludedConfigurationTypes("service2")).andReturn(Collections.<String>emptySet()).anyTimes();

    expect(request.getBlueprint()).andReturn(blueprint).anyTimes();
    expect(request.getClusterId()).andReturn(CLUSTER_ID).anyTimes();
    expect(request.getClusterName()).andReturn(CLUSTER_NAME).anyTimes();
    expect(request.getDescription()).andReturn("Provision Cluster Test").anyTimes();
    expect(request.getConfiguration()).andReturn(topoConfiguration).anyTimes();
    expect(request.getHostGroupInfo()).andReturn(groupInfoMap).anyTimes();
    expect(request.getTopologyValidators()).andReturn(topologyValidators).anyTimes();
    expect(request.getConfigRecommendationStrategy()).andReturn(ConfigRecommendationStrategy.NEVER_APPLY);
    expect(request.getProvisionAction()).andReturn(ProvisionAction.START_ONLY).anyTimes();
    expect(request.getSecurityConfiguration()).andReturn(null).anyTimes();


    expect(group1.getBlueprintName()).andReturn(BLUEPRINT_NAME).anyTimes();
    expect(group1.getCardinality()).andReturn("test cardinality").anyTimes();
    expect(group1.containsMasterComponent()).andReturn(true).anyTimes();
    expect(group1.getComponentNames()).andReturn(group1Components).anyTimes();
    expect(group1.getComponentNames(anyObject(ProvisionAction.class))).andReturn(Collections.<String>emptyList()).anyTimes();
    expect(group1.getComponents("service1")).andReturn(group1ServiceComponents.get("service1")).anyTimes();
    expect(group1.getComponents("service2")).andReturn(group1ServiceComponents.get("service1")).anyTimes();
    expect(group1.getConfiguration()).andReturn(topoGroup1Config).anyTimes();
    expect(group1.getName()).andReturn("group1").anyTimes();
    expect(group1.getServices()).andReturn(Arrays.asList("service1", "service2")).anyTimes();
    expect(group1.getStack()).andReturn(stack).anyTimes();

    expect(group2.getBlueprintName()).andReturn(BLUEPRINT_NAME).anyTimes();
    expect(group2.getCardinality()).andReturn("test cardinality").anyTimes();
    expect(group2.containsMasterComponent()).andReturn(false).anyTimes();
    expect(group2.getComponentNames()).andReturn(group2Components).anyTimes();
    expect(group2.getComponentNames(anyObject(ProvisionAction.class))).andReturn(Collections.<String>emptyList()).anyTimes();
    expect(group2.getComponents("service1")).andReturn(group2ServiceComponents.get("service1")).anyTimes();
    expect(group2.getComponents("service2")).andReturn(group2ServiceComponents.get("service2")).anyTimes();
    expect(group2.getConfiguration()).andReturn(topoGroup2Config).anyTimes();
    expect(group2.getName()).andReturn("group2").anyTimes();
    expect(group2.getServices()).andReturn(Arrays.asList("service1", "service2")).anyTimes();
    expect(group2.getStack()).andReturn(stack).anyTimes();

    // Create partial mock to allow actual logical request creation
    logicalRequestFactory = createMockBuilder(LogicalRequestFactory.class).addMockedMethod(
      LogicalRequestFactory.class.getMethod("createRequest",
        Long.class, TopologyRequest.class, ClusterTopology.class,
        TopologyLogicalRequestEntity.class)).createMock();
    Field f = TopologyManager.class.getDeclaredField("logicalRequestFactory");
    f.setAccessible(true);
    f.set(topologyManager, logicalRequestFactory);

    PowerMock.mockStatic(AmbariServer.class);
    expect(AmbariServer.getController()).andReturn(managementController).anyTimes();
    PowerMock.replay(AmbariServer.class);
    expect(managementController.getClusters()).andReturn(clusters).anyTimes();
    expect(clusters.getClusterById(anyLong())).andReturn(cluster).anyTimes();
    expect(cluster.getClusterName()).andReturn(CLUSTER_NAME).anyTimes();

    expect(ambariContext.getPersistedTopologyState()).andReturn(persistedState).anyTimes();
    //todo: don't ignore param
    ambariContext.createAmbariResources(isA(ClusterTopology.class), eq(CLUSTER_NAME), (SecurityType) isNull(), (String) isNull());
    expectLastCall().once();
    expect(ambariContext.getNextRequestId()).andReturn(1L).once();
    expect(ambariContext.isClusterKerberosEnabled(CLUSTER_ID)).andReturn(false).anyTimes();
    expect(ambariContext.getClusterId(CLUSTER_NAME)).andReturn(CLUSTER_ID).anyTimes();
    expect(ambariContext.getClusterName(CLUSTER_ID)).andReturn(CLUSTER_NAME).anyTimes();
    // so only INITIAL config
    expect(ambariContext.createConfigurationRequests(capture(configRequestPropertiesCapture))).
      andReturn(Collections.singletonList(configurationRequest));
    expect(ambariContext.createConfigurationRequests(capture(configRequestPropertiesCapture2))).
      andReturn(Collections.singletonList(configurationRequest2)).once();
    expect(ambariContext.createConfigurationRequests(capture(configRequestPropertiesCapture3))).
      andReturn(Collections.singletonList(configurationRequest3)).once();
    // INSTALL task expectation


    expect(ambariContext.createAmbariTask(anyLong(), anyLong(), eq("component3"),
      anyString(), eq(AmbariContext.TaskType.INSTALL), anyBoolean())).andReturn(hostRoleCommandInstallComponent3).times(3);
    expect(ambariContext.createAmbariTask(anyLong(), anyLong(), eq("component4"),
      anyString(), eq(AmbariContext.TaskType.INSTALL), anyBoolean())).andReturn(hostRoleCommandInstallComponent4).times(2);

    expect(hostRoleCommandInstallComponent3.getTaskId()).andReturn(1L).atLeastOnce();
    expect(hostRoleCommandInstallComponent3.getRoleCommand()).andReturn(RoleCommand.INSTALL).atLeastOnce();
    expect(hostRoleCommandInstallComponent3.getRole()).andReturn(Role.INSTALL_PACKAGES).atLeastOnce();
    expect(hostRoleCommandInstallComponent3.getStatus()).andReturn(HostRoleStatus.COMPLETED).atLeastOnce();

    expect(hostRoleCommandInstallComponent4.getTaskId()).andReturn(2L).atLeastOnce();
    expect(hostRoleCommandInstallComponent4.getRoleCommand()).andReturn(RoleCommand.INSTALL).atLeastOnce();
    expect(hostRoleCommandInstallComponent4.getRole()).andReturn(Role.INSTALL_PACKAGES).atLeastOnce();
    expect(hostRoleCommandInstallComponent4.getStatus()).andReturn(HostRoleStatus.COMPLETED).atLeastOnce();

    expect(ambariContext.createAmbariTask(anyLong(), anyLong(), eq("component1"),
      anyString(), eq(AmbariContext.TaskType.START), anyBoolean())).andReturn(hostRoleCommandStartComponent1).times
      (1);
    expect(ambariContext.createAmbariTask(anyLong(), anyLong(), eq("component2"),
      anyString(), eq(AmbariContext.TaskType.START), anyBoolean())).andReturn(hostRoleCommandStartComponent2).times(1);

    expect(hostRoleCommandStartComponent1.getTaskId()).andReturn(3L).anyTimes();
    expect(hostRoleCommandStartComponent1.getRoleCommand()).andReturn(RoleCommand.START).atLeastOnce();
    expect(hostRoleCommandStartComponent1.getRole()).andReturn(Role.DATANODE).atLeastOnce();
    expect(hostRoleCommandStartComponent1.getStatus()).andReturn(HostRoleStatus.COMPLETED).atLeastOnce();

    expect(hostRoleCommandStartComponent2.getTaskId()).andReturn(4L).anyTimes();
    expect(hostRoleCommandStartComponent2.getRoleCommand()).andReturn(RoleCommand.START).atLeastOnce();
    expect(hostRoleCommandStartComponent2.getRole()).andReturn(Role.NAMENODE).atLeastOnce();
    expect(hostRoleCommandStartComponent2.getStatus()).andReturn(HostRoleStatus.COMPLETED).atLeastOnce();


    ambariContext.setConfigurationOnCluster(capture(updateClusterConfigRequestCapture));
    expectLastCall().times(3);
    ambariContext.persistInstallStateForUI(CLUSTER_NAME, STACK_NAME, STACK_VERSION);
    expectLastCall().once();

    expect(clusterController.ensureResourceProvider(anyObject(Resource.Type.class))).andReturn(resourceProvider);
    expect(executor.submit(anyObject(AsyncCallableService.class))).andReturn(mockFuture).times(2);

    persistedTopologyRequest = new PersistedTopologyRequest(1, request);
    expect(persistedState.getAllRequests()).andReturn(Collections.<ClusterTopology,
      List<LogicalRequest>>emptyMap()).once();
    expect(persistedState.persistTopologyRequest(request)).andReturn(persistedTopologyRequest).once();
    persistedState.persistLogicalRequest((LogicalRequest) anyObject(), anyLong());
    expectLastCall().once();

    replay(blueprint, stack, request, group1, group2, ambariContext, logicalRequestFactory, logicalRequest,
      configurationRequest, configurationRequest2, configurationRequest3, requestStatusResponse, executor,
      persistedState, securityConfigurationFactory, credentialStoreService, clusterController, resourceProvider,
      mockFuture, managementController, clusters, cluster, hostRoleCommandInstallComponent3,
      hostRoleCommandInstallComponent4, hostRoleCommandStartComponent1, hostRoleCommandStartComponent2,
      serviceComponentInfo, clientComponentInfo);

    Class clazz = TopologyManager.class;

    f = clazz.getDeclaredField("executor");
    f.setAccessible(true);
    f.set(topologyManager, executor);

    EasyMockSupport.injectMocks(topologyManager);
  }

  @After
  public void tearDown() {
    verify(blueprint, stack, request, group1, group2, ambariContext, logicalRequestFactory,
      logicalRequest, configurationRequest, configurationRequest2, configurationRequest3,
      requestStatusResponse, executor, persistedState, mockFuture,
      managementController, clusters, cluster, hostRoleCommandInstallComponent3, hostRoleCommandInstallComponent4,
      hostRoleCommandStartComponent1, hostRoleCommandStartComponent2);

    reset(blueprint, stack, request, group1, group2, ambariContext, logicalRequestFactory,
      logicalRequest, configurationRequest, configurationRequest2, configurationRequest3,
      requestStatusResponse, executor, persistedState, mockFuture,
      managementController, clusters, cluster, hostRoleCommandInstallComponent3, hostRoleCommandInstallComponent4,
      hostRoleCommandStartComponent1, hostRoleCommandStartComponent2);
  }

  @Test
  public void testProvisionCluster() throws Exception {
    topologyManager.provisionCluster(request);
    LogicalRequest request = topologyManager.getRequest(1);
    assertEquals(request.getHostRequests().size(), 3);
  }
}
