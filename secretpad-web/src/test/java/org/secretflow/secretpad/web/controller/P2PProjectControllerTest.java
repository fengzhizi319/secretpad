/*
 * Copyright 2023 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.web.controller;

import org.secretflow.secretpad.common.constant.resource.ApiResourceCodeConstants;
import org.secretflow.secretpad.common.enums.ProjectStatusEnum;
import org.secretflow.secretpad.common.errorcode.ProjectErrorCode;
import org.secretflow.secretpad.common.errorcode.VoteErrorCode;
import org.secretflow.secretpad.common.util.Base64Utils;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.common.util.UserContext;
import org.secretflow.secretpad.persistence.entity.*;
import org.secretflow.secretpad.persistence.model.ParticipantNodeInstVO;
import org.secretflow.secretpad.persistence.repository.*;
import org.secretflow.secretpad.service.model.approval.VoteRequestBody;
import org.secretflow.secretpad.service.model.approval.VoteRequestMessage;
import org.secretflow.secretpad.service.model.project.ArchiveProjectRequest;
import org.secretflow.secretpad.service.model.project.CreateProjectRequest;
import org.secretflow.secretpad.service.model.project.ProjectParticipantsRequest;
import org.secretflow.secretpad.service.model.project.UpdateProjectRequest;
import org.secretflow.secretpad.web.controller.p2p.P2PProjectController;
import org.secretflow.secretpad.web.utils.FakerUtils;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;

/**
 * P2P项目控制器单元测试类
 * 用于测试P2P模式下的项目管理功能，包括创建、列表、更新、归档等操作
 *
 * @author chenmingliang
 * @date 2024/01/04
 */
public class P2PProjectControllerTest extends ControllerTest {

    /**
     * Mock注入项目仓库，用于模拟数据库操作
     * 负责项目的增删改查等持久化操作
     */
    @MockBean
    private ProjectRepository projectRepository;

    /**
     * Mock注入项目节点仓库
     * 负责项目中参与节点的管理
     */
    @MockBean
    private ProjectNodeRepository projectNodeRepository;

    /**
     * Mock注入项目机构仓库
     * 负责项目中参与机构的管理
     */
    @MockBean
    private ProjectInstRepository projectInstRepository;

    /**
     * Mock注入项目审批配置仓库
     * 负责管理项目相关的审批流程配置
     */
    @MockBean
    private ProjectApprovalConfigRepository projectApprovalConfigRepository;

    /**
     * Mock注入投票请求仓库
     * 负责管理P2P模式下的投票审批记录
     */
    @MockBean
    private VoteRequestRepository voteRequestRepository;

    /**
     * Mock注入机构仓库
     * 负责管理机构基本信息
     */
    @MockBean
    private InstRepository instRepository;

    /**
     * Mock注入项目图谱仓库
     * 负责管理项目中的计算图谱（DAG）
     */
    @MockBean
    private ProjectGraphRepository projectGraphDORepository;

    /**
     * Mock注入项目任务仓库
     * 负责管理项目中的计算任务
     */
    @MockBean
    private ProjectJobRepository projectJobRepository;

    /**
     * Mock注入节点仓库
     * 负责管理节点基本信息
     */
    @MockBean
    private NodeRepository nodeRepository;


    /**
     * 测试P2P模式下创建项目功能
     * 验证正常创建项目的流程，包括：
     * 1. 构造创建项目请求（项目名称、描述、计算模式）
     * 2. 设置API资源权限
     * 3. Mock项目已存在（模拟项目ID冲突场景）
     * 4. 调用创建项目接口并验证响应
     *
     * @throws Exception 测试异常
     */
    @Test
    void createProject() throws Exception {
        assertResponse(() -> {
            // 构造创建项目请求对象
            CreateProjectRequest request = new CreateProjectRequest();
            request.setName("test");                    // 项目名称
            request.setDescription("test project");     // 项目描述
            request.setComputeMode("mpc");              // 计算模式：MPC（多方安全计算）

            // 设置当前用户的API资源权限，允许创建项目
            UserContext.getUser().setApiResources(Set.of(ApiResourceCodeConstants.PRJ_CREATE));

            // Mock项目查询返回，模拟项目已存在的场景
            Mockito.when(projectRepository.findById(anyString())).thenReturn(Optional.of(ProjectDO.builder().projectId(PROJECT_ID).build()));
            
            // 发送POST请求到P2P项目创建接口
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "createP2PProject", CreateProjectRequest.class))
                    .content(JsonUtils.toJSONString(request));
        });
    }

    /**
     * 测试P2P模式下获取项目列表功能
     * 验证正常获取项目列表的流程，包括：
     * 1. Mock当前用户关联的项目实例
     * 2. Mock项目审批配置列表
     * 3. Mock项目详细信息
     * 4. Mock投票请求信息
     * 5. Mock项目节点、机构、图谱、任务等统计信息
     * 6. 调用列表接口并验证返回完整的项目信息
     *
     * @throws Exception 测试异常
     */
    @Test
    void listP2PProject() throws Exception {
        assertResponse(() -> {
            // Mock当前用户所在机构关联的项目实例列表
            List<ProjectInstDO> projectInstDOList = Collections.singletonList(ProjectInstDO.builder().upk(new ProjectInstDO.UPK("proj1", "inst1")).build());
            Mockito.when(projectInstRepository.findByInstId("owner_id")).thenReturn(projectInstDOList);
            
            // Mock项目审批配置列表，模拟有两个待审批的项目
            List<ProjectApprovalConfigDO> projectApprovalConfigDOS = Arrays.asList(
                    ProjectApprovalConfigDO.builder().projectId("project_id_1").voteID("vote_id_1").build(),
                    ProjectApprovalConfigDO.builder().projectId("project_id_2").voteID("vote_id_2").build()
            );
            Mockito.when(projectApprovalConfigRepository.listProjectApprovalConfigByType("PROJECT_CREATE")).thenReturn(projectApprovalConfigDOS);
            Mockito.when(projectApprovalConfigRepository.findByType("PROJECT_CREATE")).thenReturn(projectApprovalConfigDOS);
            
            // Mock项目详细信息列表，包含两个不同配置的项目
            List<ProjectDO> projects = Arrays.asList(
                    new ProjectDO("project_id_1", "Project 1", "Desc 1", "COMPUTE_MODE_1", "ccc", new ProjectInfoDO("tee_domain_1"), "owner_id_1", 0),
                    new ProjectDO("project_id_2", "Project 2", "Desc 2", "COMPUTE_MODE_2", "ccc", new ProjectInfoDO("tee_domain_2"), "owner_id_2", 2)
            );
            Mockito.when(projectRepository.findAllById(anySet())).thenReturn(projects);
            
            // 构造第一个项目的投票信息
            HashSet<VoteRequestDO.PartyVoteInfo> partyVoteInfos1 = new HashSet<>();
            HashSet<VoteRequestDO.PartyVoteInfo> partyVoteInfos2 = new HashSet<>();
            partyVoteInfos1.add(VoteRequestDO.PartyVoteInfo.builder().partyId("inst_id_1").action("ss").reason("xxx").build());
            partyVoteInfos2.add(VoteRequestDO.PartyVoteInfo.builder().partyId("inst_id_2").action("ss").reason("xxx").build());
            
            // 构造投票请求对象
            VoteRequestDO voteRequestDO1 = FakerUtils.fake(VoteRequestDO.class);
            VoteRequestDO voteRequestDO2 = FakerUtils.fake(VoteRequestDO.class);
            voteRequestDO1.setVoteID("vote_id_1");
            voteRequestDO1.setPartyVoteInfos(partyVoteInfos1);
            voteRequestDO2.setVoteID("vote_id_2");
            voteRequestDO2.setPartyVoteInfos(partyVoteInfos2);
            List<VoteRequestDO> voteRequestDOS = Arrays.asList(
                    voteRequestDO1, voteRequestDO2
            );
            Mockito.when(voteRequestRepository.findAllById(anyCollection())).thenReturn(voteRequestDOS);
            
            // Mock项目的节点、机构、图谱、任务等关联数据为空
            Mockito.when(projectNodeRepository.findProjectionByProjectId(anyString())).thenReturn(Collections.emptyList());
            Mockito.when(projectInstRepository.findProjectionByProjectId(anyString())).thenReturn(Collections.emptyList());
            Mockito.when(projectGraphDORepository.countByProjectId(anyString())).thenReturn(0);
            Mockito.when(projectJobRepository.countByProjectId(anyString())).thenReturn(0);
            Mockito.when(nodeRepository.findByNodeIdIn(anyList())).thenReturn(Collections.emptyList());
            
            // 发送POST请求获取P2P项目列表
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "listP2PProject"));

        });
    }

    /**
     * 测试获取P2P项目列表时投票记录不存在的异常场景
     * 验证当项目关联的投票ID在投票仓库中找不到时，返回PROJECT_VOTE_NOT_EXISTS错误码
     *
     * 测试场景：
     * - 项目审批配置中存在投票ID
     * - 但投票仓库中无法找到对应的投票记录
     * - 预期返回VOTE_NOT_EXISTS错误
     *
     * @throws Exception 测试异常
     */
    @Test
    void listP2PProject_PROJECT_VOTE_NOT_EXISTS() throws Exception {
        assertErrorCode(() -> {
            // Mock当前用户所在机构关联的项目实例列表
            List<ProjectInstDO> projectInstDOList = Collections.singletonList(ProjectInstDO.builder().upk(new ProjectInstDO.UPK("proj1", "inst1")).build());
            Mockito.when(projectInstRepository.findByInstId("owner_id")).thenReturn(projectInstDOList);
            
            // Mock项目审批配置列表
            List<ProjectApprovalConfigDO> projectApprovalConfigDOS = Arrays.asList(
                    ProjectApprovalConfigDO.builder().projectId("project_id_1").voteID("vote_id_1").build(),
                    ProjectApprovalConfigDO.builder().projectId("project_id_2").voteID("vote_id_2").build()
            );
            Mockito.when(projectApprovalConfigRepository.listProjectApprovalConfigByType("PROJECT_CREATE")).thenReturn(projectApprovalConfigDOS);
            Mockito.when(projectApprovalConfigRepository.findByType("PROJECT_CREATE")).thenReturn(projectApprovalConfigDOS);
            
            // Mock项目详细信息（使用不同的项目ID，与投票ID不匹配）
            List<ProjectDO> projects = Arrays.asList(
                    new ProjectDO("project_id_3", "Project 1", "Desc 1", "COMPUTE_MODE_1", "ccc", new ProjectInfoDO("tee_domain_1"), "owner_id_1", 0),
                    new ProjectDO("project_id_4", "Project 2", "Desc 2", "COMPUTE_MODE_2", "ccc", new ProjectInfoDO("tee_domain_2"), "owner_id_2", 2)
            );
            Mockito.when(projectRepository.findAllById(anySet())).thenReturn(projects);
            
            // 构造投票信息（但这些投票记录实际上不存在于仓库中）
            HashSet<VoteRequestDO.PartyVoteInfo> partyVoteInfos1 = new HashSet<>();
            HashSet<VoteRequestDO.PartyVoteInfo> partyVoteInfos2 = new HashSet<>();
            partyVoteInfos1.add(VoteRequestDO.PartyVoteInfo.builder().partyId("inst_id_1").action("ss").reason("xxx").build());
            partyVoteInfos2.add(VoteRequestDO.PartyVoteInfo.builder().partyId("inst_id_2").action("ss").reason("xxx").build());
            VoteRequestDO voteRequestDO1 = FakerUtils.fake(VoteRequestDO.class);
            VoteRequestDO voteRequestDO2 = FakerUtils.fake(VoteRequestDO.class);
            voteRequestDO1.setVoteID("vote_id_1");
            voteRequestDO1.setPartyVoteInfos(partyVoteInfos1);
            voteRequestDO2.setVoteID("vote_id_2");
            voteRequestDO2.setPartyVoteInfos(partyVoteInfos2);
            List<VoteRequestDO> voteRequestDOS = Arrays.asList(
                    voteRequestDO1, voteRequestDO2
            );
            // 注意：这里Mock返回空列表，导致投票记录找不到
            Mockito.when(voteRequestRepository.findAllById(anyCollection())).thenReturn(voteRequestDOS);
            Mockito.when(projectNodeRepository.findProjectionByProjectId(anyString())).thenReturn(Collections.emptyList());
            Mockito.when(projectInstRepository.findProjectionByProjectId(anyString())).thenReturn(Collections.emptyList());
            Mockito.when(projectGraphDORepository.countByProjectId(anyString())).thenReturn(0);
            Mockito.when(projectJobRepository.countByProjectId(anyString())).thenReturn(0);
            Mockito.when(nodeRepository.findByNodeIdIn(anyList())).thenReturn(Collections.emptyList());
            
            // 发送POST请求，预期返回PROJECT_VOTE_NOT_EXISTS错误
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "listP2PProject"));

        }, VoteErrorCode.PROJECT_VOTE_NOT_EXISTS);
    }

    /**
     * 构建测试用的项目节点对象
     * 用于后续测试中Mock项目节点的查询结果
     *
     * @return 项目节点DO对象，包含项目ID和节点名称
     */
    private ProjectNodeDO buildProjectNodeDO() {
        return ProjectNodeDO.builder().upk(new ProjectNodeDO.UPK(PROJECT_ID, "alice")).build();
    }

    /**
     * 构建测试用的项目对象
     * 用于后续测试中Mock项目的查询结果
     *
     * @return 项目DO对象，包含项目ID和所有者ID
     */
    private ProjectDO buildProjectDO() {
        return ProjectDO.builder().projectId(PROJECT_ID).ownerId("test").build();
    }

    /**
     * 测试P2P模式下更新项目功能
     * 验证正常更新项目的流程，包括：
     * 1. 构造更新项目请求（使用FakerUtils生成随机数据）
     * 2. 设置API资源权限
     * 3. Mock项目节点存在
     * 4. Mock项目存在
     * 5. 调用更新接口并验证返回空数据
     *
     * @throws Exception 测试异常
     */
    @Test
    void updateProject() throws Exception {
        assertResponseWithEmptyData(() -> {
            // 使用FakerUtils生成随机的更新请求数据
            UpdateProjectRequest request = FakerUtils.fake(UpdateProjectRequest.class);
            request.setProjectId(PROJECT_ID);  // 设置要更新的项目ID

            // 设置当前用户的API资源权限，允许更新项目
            UserContext.getUser().setApiResources(Set.of(ApiResourceCodeConstants.PRJ_UPDATE));
            
            // Mock项目节点查询返回，验证节点权限
            Mockito.when(projectNodeRepository.findById(Mockito.any())).thenReturn(Optional.of(buildProjectNodeDO()));
            // Mock项目查询返回，确保项目存在
            Mockito.when(projectRepository.findById(anyString())).thenReturn(Optional.of(buildProjectDO()));
            
            // 发送POST请求到P2P项目更新接口
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "updateProject", UpdateProjectRequest.class)).
                    content(JsonUtils.toJSONString(request));
        });
    }

    /**
     * 测试P2P模式下归档项目功能
     * 验证正常归档项目的流程，包括：
     * 1. 构造归档项目请求
     * 2. 设置项目状态为REVIEWING（审核中）
     * 3. 设置API资源权限
     * 4. Mock项目节点和项目存在
     * 5. 调用归档接口并验证返回空数据
     *
     * @throws Exception 测试异常
     */
    @Test
    void projectArchive() throws Exception {
        assertResponseWithEmptyData(() -> {
            // 使用FakerUtils生成随机的归档请求数据
            ArchiveProjectRequest request = FakerUtils.fake(ArchiveProjectRequest.class);
            request.setProjectId(PROJECT_ID);  // 设置要归档的项目ID
            
            // 构建项目对象，并设置状态为审核中
            ProjectDO projectDO = buildProjectDO();
            projectDO.setStatus(ProjectStatusEnum.REVIEWING.getCode());
            
            // 设置当前用户的API资源权限，允许归档项目
            UserContext.getUser().setApiResources(Set.of(ApiResourceCodeConstants.PRJ_ARCHIVE));
            
            // Mock项目节点查询返回，验证节点权限
            Mockito.when(projectNodeRepository.findById(Mockito.any())).thenReturn(Optional.of(buildProjectNodeDO()));
            // Mock项目查询返回
            Mockito.when(projectRepository.findById(anyString())).thenReturn(Optional.of(projectDO));
            
            // 发送POST请求到P2P项目归档接口
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "projectArchive", ArchiveProjectRequest.class)).
                    content(JsonUtils.toJSONString(request));
        });
    }

    /**
     * 测试获取P2P项目参与者信息功能
     * 验证正常获取项目参与者的流程，包括：
     * 1. 构造投票ID请求
     * 2. Mock投票请求记录存在
     * 3. Mock项目审批配置存在
     * 4. Mock节点和机构信息
     * 5. Mock项目详细信息
     * 6. 调用接口并验证返回完整的参与者信息（发起方和受邀方）
     *
     * @throws Exception 测试异常
     */
    @Test
    void projectParticipants() throws Exception {
        assertResponse(() -> {

            String voteId = "vote123";  // 投票ID
            ProjectParticipantsRequest request = new ProjectParticipantsRequest(voteId);
            
            // 构造投票请求对象
            VoteRequestDO voteRequestDO = new VoteRequestDO();
            VoteRequestDO.PartyVoteInfo partyVoteInfo = new VoteRequestDO.PartyVoteInfo("inst_id", "action", "reason");
            voteRequestDO.setPartyVoteInfos(Collections.singleton(partyVoteInfo));
            
            // 构造投票请求体，设置发起方为alice
            VoteRequestBody voteRequestBody = VoteRequestBody.builder().initiator("alice").build();
            String voteRequestBodyBase64 = Base64Utils.encode(JsonUtils.toJSONString(voteRequestBody).getBytes());
            VoteRequestMessage voteRequestMessage = VoteRequestMessage.builder().body(voteRequestBodyBase64).build();
            voteRequestDO.setRequestMsg(JsonUtils.toJSONString(voteRequestMessage));
            Mockito.when(voteRequestRepository.findById(voteId)).thenReturn(Optional.of(voteRequestDO));
            
            // 构造参与者节点和机构信息
            ParticipantNodeInstVO participantNodeInstVO = new ParticipantNodeInstVO();
            participantNodeInstVO.setInitiatorNodeId("alice");              // 发起方节点ID
            participantNodeInstVO.setInitiatorNodeName("alicename");        // 发起方节点名称
            participantNodeInstVO.setInvitees(List.of(new ParticipantNodeInstVO.NodeInstVO("bob", "bobname", "bob-inst", "bob-inst-name")));

            List<ParticipantNodeInstVO> participantNodeInstVOS = new ArrayList<>();
            participantNodeInstVOS.add(participantNodeInstVO);
            
            // 构造项目审批配置
            ProjectApprovalConfigDO projectApprovalConfigDO = new ProjectApprovalConfigDO();
            projectApprovalConfigDO.setProjectId("project123");
            projectApprovalConfigDO.setParticipantNodeInfo(participantNodeInstVOS);
            
            // Mock节点查询返回
            Mockito.when(nodeRepository.findByNodeId(anyString())).thenReturn(NodeDO.builder().name("alice-inst").instId("inst_id").build());
            // Mock项目审批配置查询返回
            Mockito.when(projectApprovalConfigRepository.findById(voteId)).thenReturn(Optional.of(projectApprovalConfigDO));
            // Mock机构查询返回
            Mockito.when(instRepository.findByInstIdIn(anyList())).thenReturn(List.of(InstDO.builder().instId("inst_id").name("inst_name").build()));
            Mockito.when(instRepository.findByInstId("inst_id")).thenReturn(InstDO.builder().instId("inst_id").name("inst_name").build());
            
            // 构造项目详细信息
            ProjectDO projectDO = new ProjectDO();
            projectDO.setName("Test Project");
            projectDO.setDescription("Test Description");
            projectDO.setComputeFunc("Test Compute Func");
            projectDO.setComputeMode("Test Compute Mode");
            
            // Mock机构查询和项目查询
            Mockito.when(instRepository.findByInstId(voteRequestBody.getInitiator())).thenReturn(InstDO.builder().instId("inst_id").name("inst_name").build());
            Mockito.when(projectRepository.findById("project123")).thenReturn(Optional.of(projectDO));
            
            // 发送POST请求获取项目参与者信息
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "projectParticipants", ProjectParticipantsRequest.class)).
                    content(JsonUtils.toJSONString(request));
        });
    }

    /**
     * 测试获取项目参与者时投票记录不存在的异常场景
     * 验证当投票ID在投票仓库中找不到时，返回VOTE_NOT_EXISTS错误码
     *
     * 测试场景：
     * - 请求中传入投票ID
     * - 但投票仓库中无法找到对应的投票记录
     * - 预期返回VOTE_NOT_EXISTS错误
     *
     * @throws Exception 测试异常
     */
    @Test
    void projectParticipantsError_VOTE_NOT_EXISTS() throws Exception {
        assertErrorCode(() -> {
            String voteId = "vote123";  // 投票ID
            ProjectParticipantsRequest request = new ProjectParticipantsRequest(voteId);
            
            // 构造投票请求对象（但实际上不会被使用，因为Mock返回空）
            VoteRequestDO voteRequestDO = new VoteRequestDO();
            VoteRequestDO.PartyVoteInfo partyVoteInfo = new VoteRequestDO.PartyVoteInfo("inst_id", "action", "reason");
            voteRequestDO.setPartyVoteInfos(Collections.singleton(partyVoteInfo));
            VoteRequestBody voteRequestBody = VoteRequestBody.builder().initiator("alice").build();
            String voteRequestBodyBase64 = Base64Utils.encode(JsonUtils.toJSONString(voteRequestBody).getBytes());
            VoteRequestMessage voteRequestMessage = VoteRequestMessage.builder().body(voteRequestBodyBase64).build();
            voteRequestDO.setRequestMsg(JsonUtils.toJSONString(voteRequestMessage));
            
            // Mock投票仓库返回空，模拟投票记录不存在
            Mockito.when(voteRequestRepository.findById(voteId)).thenReturn(Optional.empty());
            
            ProjectApprovalConfigDO projectApprovalConfigDO = new ProjectApprovalConfigDO();
            projectApprovalConfigDO.setProjectId("project123");
            projectApprovalConfigDO.setParticipantNodeInfo(new ArrayList<>());
            Mockito.when(projectApprovalConfigRepository.findById(voteId)).thenReturn(Optional.of(projectApprovalConfigDO));
            
            ProjectDO projectDO = new ProjectDO();
            projectDO.setName("Test Project");
            projectDO.setDescription("Test Description");
            projectDO.setComputeFunc("Test Compute Func");
            projectDO.setComputeMode("Test Compute Mode");
            Mockito.when(projectRepository.findById("project123")).thenReturn(Optional.of(projectDO));

            // 发送POST请求，预期返回VOTE_NOT_EXISTS错误
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "projectParticipants", ProjectParticipantsRequest.class)).
                    content(JsonUtils.toJSONString(request));
        }, VoteErrorCode.VOTE_NOT_EXISTS);
    }

    /**
     * 测试获取项目参与者时项目审批配置不存在的异常场景
     * 验证当投票ID在项目审批配置仓库中找不到时，返回VOTE_NOT_EXISTS错误码
     *
     * 测试场景：
     * - 投票记录存在
     * - 但项目审批配置仓库中无法找到对应的配置
     * - 预期返回VOTE_NOT_EXISTS错误
     *
     * @throws Exception 测试异常
     */
    @Test
    void projectParticipantsError_PROJECT_APPROVAL_NOT_EXISTS() throws Exception {
        assertErrorCode(() -> {
            String voteId = "vote123";  // 投票ID
            ProjectParticipantsRequest request = new ProjectParticipantsRequest(voteId);
            
            // 构造投票请求对象
            VoteRequestDO voteRequestDO = new VoteRequestDO();
            VoteRequestDO.PartyVoteInfo partyVoteInfo = new VoteRequestDO.PartyVoteInfo("inst_id", "action", "reason");
            voteRequestDO.setPartyVoteInfos(Collections.singleton(partyVoteInfo));
            VoteRequestBody voteRequestBody = VoteRequestBody.builder().initiator("alice").build();
            String voteRequestBodyBase64 = Base64Utils.encode(JsonUtils.toJSONString(voteRequestBody).getBytes());
            VoteRequestMessage voteRequestMessage = VoteRequestMessage.builder().body(voteRequestBodyBase64).build();
            voteRequestDO.setRequestMsg(JsonUtils.toJSONString(voteRequestMessage));
            
            // Mock投票记录存在
            Mockito.when(voteRequestRepository.findById(voteId)).thenReturn(Optional.of(voteRequestDO));
            
            // 构造参与者节点和机构信息
            ParticipantNodeInstVO participantNodeInstVO = new ParticipantNodeInstVO();
            participantNodeInstVO.setInitiatorNodeId("alice");
            participantNodeInstVO.setInitiatorNodeName("alicename");
            participantNodeInstVO.setInvitees(List.of(new ParticipantNodeInstVO.NodeInstVO("bob", "bobname", "bob-inst", "bob-inst-name")));

            List<ParticipantNodeInstVO> participantNodeInstVOS = new ArrayList<>();
            participantNodeInstVOS.add(participantNodeInstVO);
            
            ProjectApprovalConfigDO projectApprovalConfigDO = new ProjectApprovalConfigDO();
            projectApprovalConfigDO.setProjectId("project123");
            projectApprovalConfigDO.setParticipantNodeInfo(participantNodeInstVOS);
            
            // Mock节点查询返回
            Mockito.when(nodeRepository.findByNodeId(anyString())).thenReturn(NodeDO.builder().name("alice-inst").instId("inst_id").build());
            // Mock项目审批配置不存在
            Mockito.when(projectApprovalConfigRepository.findById(voteId)).thenReturn(Optional.empty());
            
            // Mock机构查询返回
            Mockito.when(instRepository.findByInstIdIn(anyList())).thenReturn(List.of(InstDO.builder().instId("inst_id").name("inst_name").build()));
            Mockito.when(instRepository.findByInstId("inst_id")).thenReturn(InstDO.builder().instId("inst_id").name("inst_name").build());
            
            // 构造项目详细信息
            ProjectDO projectDO = new ProjectDO();
            projectDO.setName("Test Project");
            projectDO.setDescription("Test Description");
            projectDO.setComputeFunc("Test Compute Func");
            projectDO.setComputeMode("Test Compute Mode");
            Mockito.when(instRepository.findByInstId(voteRequestBody.getInitiator())).thenReturn(InstDO.builder().instId("inst_id").name("inst_name").build());
            Mockito.when(projectRepository.findById("project123")).thenReturn(Optional.empty());

            // 发送POST请求，预期返回VOTE_NOT_EXISTS错误
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "projectParticipants", ProjectParticipantsRequest.class)).
                    content(JsonUtils.toJSONString(request));
        }, VoteErrorCode.VOTE_NOT_EXISTS);
    }


    /**
     * 测试获取项目参与者时项目不存在的异常场景
     * 验证当项目ID在项目仓库中找不到时，返回PROJECT_NOT_EXISTS错误码
     *
     * 测试场景：
     * - 投票记录存在
     * - 项目审批配置存在
     * - 但项目仓库中无法找到对应的项目
     * - 预期返回PROJECT_NOT_EXISTS错误
     *
     * @throws Exception 测试异常
     */
    @Test
    void projectParticipantsError_PROJECT_NOT_EXISTS() throws Exception {
        assertErrorCode(() -> {
            String voteId = "vote123";  // 投票ID
            ProjectParticipantsRequest request = new ProjectParticipantsRequest(voteId);
            
            // 构造投票请求对象
            VoteRequestDO voteRequestDO = new VoteRequestDO();
            VoteRequestDO.PartyVoteInfo partyVoteInfo = new VoteRequestDO.PartyVoteInfo("inst_id", "action", "reason");
            voteRequestDO.setPartyVoteInfos(Collections.singleton(partyVoteInfo));
            VoteRequestBody voteRequestBody = VoteRequestBody.builder().initiator("alice").build();
            String voteRequestBodyBase64 = Base64Utils.encode(JsonUtils.toJSONString(voteRequestBody).getBytes());
            VoteRequestMessage voteRequestMessage = VoteRequestMessage.builder().body(voteRequestBodyBase64).build();
            voteRequestDO.setRequestMsg(JsonUtils.toJSONString(voteRequestMessage));
            
            // Mock投票记录存在
            Mockito.when(voteRequestRepository.findById(voteId)).thenReturn(Optional.of(voteRequestDO));
            
            // 构造参与者节点和机构信息
            ParticipantNodeInstVO participantNodeInstVO = new ParticipantNodeInstVO();
            participantNodeInstVO.setInitiatorNodeId("alice");
            participantNodeInstVO.setInitiatorNodeName("alicename");
            participantNodeInstVO.setInvitees(List.of(new ParticipantNodeInstVO.NodeInstVO("bob", "bobname", "bob-inst", "bob-inst-name")));

            List<ParticipantNodeInstVO> participantNodeInstVOS = new ArrayList<>();
            participantNodeInstVOS.add(participantNodeInstVO);
            
            // 构造项目审批配置
            ProjectApprovalConfigDO projectApprovalConfigDO = new ProjectApprovalConfigDO();
            projectApprovalConfigDO.setProjectId("project123");
            projectApprovalConfigDO.setParticipantNodeInfo(participantNodeInstVOS);
            
            // Mock节点查询返回
            Mockito.when(nodeRepository.findByNodeId(anyString())).thenReturn(NodeDO.builder().name("alice-inst").instId("inst_id").build());
            // Mock项目审批配置存在
            Mockito.when(projectApprovalConfigRepository.findById(voteId)).thenReturn(Optional.of(projectApprovalConfigDO));
            
            // Mock机构查询返回
            Mockito.when(instRepository.findByInstIdIn(anyList())).thenReturn(List.of(InstDO.builder().instId("inst_id").name("inst_name").build()));
            Mockito.when(instRepository.findByInstId("inst_id")).thenReturn(InstDO.builder().instId("inst_id").name("inst_name").build());
            
            // 构造项目详细信息
            ProjectDO projectDO = new ProjectDO();
            projectDO.setName("Test Project");
            projectDO.setDescription("Test Description");
            projectDO.setComputeFunc("Test Compute Func");
            projectDO.setComputeMode("Test Compute Mode");
            
            // Mock机构查询返回
            Mockito.when(instRepository.findByInstId(voteRequestBody.getInitiator())).thenReturn(InstDO.builder().instId("inst_id").name("inst_name").build());
            // Mock项目不存在
            Mockito.when(projectRepository.findById("project123")).thenReturn(Optional.empty());

            // 发送POST请求，预期返回PROJECT_NOT_EXISTS错误
            return MockMvcRequestBuilders.post(getMappingUrl(P2PProjectController.class, "projectParticipants", ProjectParticipantsRequest.class)).
                    content(JsonUtils.toJSONString(request));
        }, ProjectErrorCode.PROJECT_NOT_EXISTS);
    }

}
