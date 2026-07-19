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
package org.secretflow.secretpad.service.impl;


import org.secretflow.secretpad.common.constant.KusciaDataSourceConstants;
import org.secretflow.secretpad.common.dto.UserContextDTO;
import org.secretflow.secretpad.common.enums.PlatformTypeEnum;
import org.secretflow.secretpad.common.errorcode.AuthErrorCode;
import org.secretflow.secretpad.common.errorcode.DataErrorCode;
import org.secretflow.secretpad.common.errorcode.NodeErrorCode;
import org.secretflow.secretpad.common.errorcode.SystemErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.*;
import org.secretflow.secretpad.manager.integration.data.AbstractDataManager;
import org.secretflow.secretpad.manager.integration.model.NodeResultDTO;
import org.secretflow.secretpad.manager.integration.node.AbstractNodeManager;
import org.secretflow.secretpad.persistence.model.ResultKind;
import org.secretflow.secretpad.service.DataService;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.model.data.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.secretflow.secretpad.common.constant.DomainDatasourceConstants.DEFAULT_DATASOURCE;
import static org.secretflow.secretpad.common.constant.DomainDatasourceConstants.DEFAULT_DATASOURCE_TYPE;

/**
 * Data service implementation class
 * <p>
 * 数据服务实现类，负责 SecretPad 本地数据文件的生命周期管理：
 * <ul>
 *     <li>CSV 文件上传：接收前端 multipart 文件，落盘到 {@code secretpad.data.dir-path}/{nodeId}/ 目录，并返回默认数据源信息。</li>
 *     <li>数据元信息创建：在 P2P/Autonomy 模式下校验只能向本节点上传，随后委托 {@link AbstractDataManager} 创建 DomainData。</li>
 *     <li>结果文件下载：根据 nodeId + domainDataId 查找本地结果文件，按结果类型（CSV/Model/Rule/目录）打包或直接返回。</li>
 *     <li>默认数据源查询：向前端暴露 Kuscia 默认 localfs 数据源 {@code default-data-source}。</li>
 * </ul>
 *
 * @author : xiaonan.fhn
 * @date 2023/5/25
 */
@Service
public class DataServiceImpl implements DataService {

    private final static Logger LOGGER = LoggerFactory.getLogger(DataServiceImpl.class);

    /**
     * 当前仅支持 .csv 文件上传。
     */
    private final static List<String> SUPPORT_FILE_TYPE = List.of(".csv");

    /**
     * 用于生成上传文件随机后缀的安全随机数生成器，种子为当前时间戳。
     */
    private final static SecureRandom RANDOM = new SecureRandom(TypeConvertUtils.long2Bytes(System.currentTimeMillis()));

    /**
     * 文件路径分隔符。
     */
    private final static String FILE_SEPETATOR = "/";

    @Autowired
    private AbstractDataManager dataManager;

    @Autowired
    private AbstractNodeManager nodeManager;

    @Autowired
    private EnvService envService;

    /**
     * 本地数据文件存储根目录，默认值为 /app/data/。
     * 实际落盘路径为：{@code storeDir}/{nodeId}/{randomFileName}.csv
     */
    @Value("${secretpad.data.dir-path:/app/data/}")
    private String storeDir;

    /**
     * 上传本地 CSV 数据文件。
     * <p>
     * 执行流程：
     * <ol>
     *     <li>调用 {@link #checkDataPermissions(String)} 校验当前用户是否有权限向该 nodeId 上传数据。</li>
     *     <li>调用 {@link #fileNameCheck(String)} 校验文件名非空、不含路径分隔符、且后缀为 .csv。</li>
     *     <li>调用 {@link #nodeIdValidCheck(String)} 校验 nodeId 不含非法字符，防止路径穿越。</li>
     *     <li>构造目标目录 {@code storeDir + nodeId + "/"}。</li>
     *     <li>最多尝试 5 次生成随机文件名（{@link #getRandomFileName(String)}），确保目标文件不存在。</li>
     *     <li>调用 {@link SafeFileUtils#checkPathInWhitelist(File, List)} 确保目标文件位于白名单目录 {@code storeDir} 下。</li>
     *     <li>若 5 次均未生成不存在的文件名，抛出 {@link DataErrorCode#FILE_EXISTS_ERROR}。</li>
     *     <li>创建目录（不存在时），将上传文件 transferTo 目标路径。</li>
     *     <li>调用 {@link FileUtils#removeBOMFromFile(String)} 去除文件 BOM 头，避免解析异常。</li>
     *     <li>返回 {@link UploadDataResultVO}，包含原始文件名、随机文件名、默认数据源 ID 及类型。</li>
     * </ol>
     *
     * @param file   前端上传的 multipart 文件
     * @param nodeId 目标节点 ID
     * @return 上传结果，供前端继续调用 datatable 创建接口
     */
    @Override
    public UploadDataResultVO upload(MultipartFile file, String nodeId) {
        checkDataPermissions(nodeId);
        String fileName = file.getOriginalFilename();
        fileNameCheck(fileName);
        nodeIdValidCheck(nodeId);
        String dirPath = Path.of(storeDir, nodeId).toString() + FILE_SEPETATOR;
        String randomFileName = null;
        File target = null;
        // 最多重试 5 次生成随机文件名，避免极低概率的命名冲突
        for (int i = 0; i < 5; i++) {
            randomFileName = getRandomFileName(fileName);
            target = new File(dirPath + randomFileName);
            if (!target.exists()) {
                break;
            }
        }
        SafeFileUtils.checkPathInWhitelist(target, List.of(storeDir));
        if (target.exists()) {
            LOGGER.warn("After try some times generate random file name, the target random file {} still exists.", dirPath + randomFileName);
            throw SecretpadException.of(DataErrorCode.FILE_EXISTS_ERROR);
        }
        createDirIfNotExist(dirPath);
        try {
            file.transferTo(target);
            FileUtils.removeBOMFromFile(dirPath + randomFileName);
        } catch (IOException e) {
            LOGGER.error("IOException: {}", e.getMessage());
            throw SecretpadException.of(SystemErrorCode.UNKNOWN_ERROR, e);
        }
        return UploadDataResultVO.builder()
                .name(fileName)
                .realName(randomFileName)
                // 本地上传的文件统一使用 Kuscia 默认数据源 default-data-source（localfs）
                .datasource(DEFAULT_DATASOURCE)
                .datasourceType(DEFAULT_DATASOURCE_TYPE)
                .build();
    }

    /**
     * 创建数据元信息（DomainData）。
     * <p>
     * 在 P2P/Autonomy 模式下，当前实现仅允许将数据注册到本平台节点（即 master 节点），
     * 因此会校验 {@code request.getNodeId()} 必须等于 {@link EnvService#getPlatformNodeId()}。
     * 校验通过后，将参数委托给 {@link AbstractDataManager#createData(String, String, String, String, String, String, String, List, List)}
     * 完成 DomainData CR 的创建。
     *
     * @param request 创建数据请求，包含 nodeId、文件名、表名、schema、数据源信息等
     * @return 创建结果（通常为空或提示信息，由 dataManager 决定）
     */
    @Override
    public String createData(CreateDataRequest request) {
        // In p2p mode, only local data can be uploaded to the master node
        // 在 P2P/Autonomy 模式下，仅允许向本平台的 master 节点上传/注册数据
        if (!request.getNodeId().equals(envService.getPlatformNodeId()) && envService.isAutonomy()) {
            LOGGER.error("The nodeId is not the platform node id.");
            throw SecretpadException.of(NodeErrorCode.NODE_NOT_EXIST_ERROR);
        }
        return dataManager.createData(
                request.getNodeId(),
                request.getName(),
                request.getRealName(),
                request.getTableName(),
                request.getDescription(),
                request.getDatasourceType(),
                request.getDatasourceName(),
                request.getDatatableSchema(),
                request.getNullStrs()
        );
    }


    /**
     * 根据 nodeId 与 domainDataId 下载结果文件。
     * <p>
     * 执行流程：
     * <ol>
     *     <li>通过 {@link AbstractNodeManager#getNodeResult(String, String)} 获取节点结果元信息 {@link NodeResultDTO}，
     *         其中包含结果文件的 relativeUri 与结果类型 resultKind。</li>
     *     <li>调用 {@link #relativeUriValidCheck(String)} 校验 relativeUri 非空且不包含 {@code ..} 等非法字符。</li>
     *     <li>构造本地文件路径 {@code storeDir + nodeId + "/" + relativeUri}。</li>
     *     <li>若文件不存在：
     *         <ul>
     *             <li>创建父目录；</li>
     *             <li>创建一个空文件占位，避免前端下载异常（当前策略）。</li>
     *         </ul>
     *     </li>
     *     <li>通过 {@link File#getCanonicalPath()} 校验文件必须位于 {@code storeDir}/{nodeId} 目录下，防止路径穿越。</li>
     *     <li>根据文件类型及结果类型构造返回：
     *         <ul>
     *             <li>目录：调用 {@link CompressUtils#compress(String, String, String)} 打包为 tar.gz。</li>
     *             <li>结果类型为 Model 或 Rule：调用 {@link CompressUtils#compressTar(List, String, String, String)} 打包为 tar.gz。</li>
     *             <li>其他（通常为 CSV）：直接读取原始 CSV 文件，返回文件名统一加 .csv 后缀。</li>
     *         </ul>
     *     </li>
     *     <li>返回 {@link DownloadInfo}，包含文件名、文件长度与输入流。</li>
     * </ol>
     *
     * @param request 下载请求，包含 nodeId 与 domainDataId
     * @return 下载信息，供 Controller 写入响应流
     */
    @Override
    public DownloadInfo download(DownloadDataRequest request) {
        NodeResultDTO nodeResult = nodeManager.getNodeResult(request.getNodeId(), request.getDomainDataId());
        String relativeUri = nodeResult.getRelativeUri();
        relativeUriValidCheck(relativeUri);
        String dirPath = Path.of(storeDir, request.getNodeId()).toString();
        String dir = dirPath + FILE_SEPETATOR;
        String filePath = dir + relativeUri;
        File f = new File(filePath);
        try {
            if (!f.exists()) {
                LOGGER.warn("The result ralative uri file {} not exits.", filePath);
                // Todo: the result so far is that an empty file is returned if it does not exist
                // 当前策略：结果文件不存在时创建一个空文件返回，避免下载接口异常
                if (!f.getParentFile().exists()) {
                    f.getParentFile().mkdirs();
                }
                if (!f.createNewFile()) {
                    LOGGER.error("failed to create empty file.");
                    throw SecretpadException.of(SystemErrorCode.UNKNOWN_ERROR, "failed to create empty file for return.");
                }
            }
            // Security Recommendation Verification The download file path will not be overridden and is a subdirectory in the storeDir directory
            // 安全校验：通过规范化路径确保待下载文件位于 storeDir/{nodeId} 目录下，防止 relativeUri 穿越到其他目录
            File dirPathFile = new File(dirPath);
            if (!f.getCanonicalPath().startsWith(dirPathFile.getCanonicalPath())) {
                LOGGER.error("The result ralative uri file {} is not in the storeDir {}", filePath, dir);
                throw SecretpadException.of(DataErrorCode.FILE_NOT_EXISTS_ERROR);
            }
            String fileName = null;
            int fileLength = 0;
            InputStream inputStream = null;
            if (f.isDirectory()) {
                // 结果为目录时，整体打包为 tar.gz 返回
                LOGGER.info("Download process got a dir to download, whose relative uri = {}", relativeUri);
                CompressUtils.compress(filePath, dir, relativeUri);
                fileName = relativeUri + ".tar.gz";
                // since it is a new compressed file, add a suffix
                inputStream = new FileInputStream(dir + fileName);
                fileLength = (int) new File(dir + fileName).length();
            } else {
                ResultKind kind = ResultKind.fromDatatable(nodeResult.getResultKind());
                switch (kind) {
                    case Model:
                    case Rule: {
                        //model and rule
                        // 模型或规则文件打包为 tar.gz，方便前端一次性下载
                        CompressUtils.compressTar(List.of(new File(filePath)), filePath, dir, relativeUri);
                        // a new compressed file, add a suffix
                        fileName = relativeUri + ".tar.gz";
                        inputStream = new FileInputStream(dir + fileName);
                        fileLength = (int) new File(dir + fileName).length();
                        break;
                    }
                    default: {
                        LOGGER.info("Download process got a  real csv file to download, whose relative uri = {}", relativeUri);
                        fileName = relativeUri + ".csv";
                        // since the source file is already csv, there is no need to add a suffix, but the file name returned above is suffixed
                        // 源文件本身即为 CSV，直接读取；返回文件名统一补充 .csv 后缀
                        inputStream = new FileInputStream(dir + relativeUri);
                        fileLength = inputStream.available();
                        break;
                    }
                }
            }
            LOGGER.info("When download, the ralative uri = {}. the real file path = {}", relativeUri, filePath);
            return DownloadInfo.builder()
                    .fileName(fileName)
                    .fileLength(fileLength)
                    .inputStream(inputStream)
                    .build();
        } catch (IOException e) {
            LOGGER.error("IO exception: {}", e.getMessage());
            throw SecretpadException.of(SystemErrorCode.UNKNOWN_ERROR, e);
        } catch (Exception e) {
            LOGGER.error("got Exception: {}", e.getMessage());
            throw SecretpadException.of(SystemErrorCode.UNKNOWN_ERROR, e);
        }
    }

    /**
     * Create directory if not exists
     * <p>
     * 若目标目录不存在则创建；创建失败则抛出 UNKNOWN_ERROR。
     *
     * @param dir target directory path
     */
    private void createDirIfNotExist(String dir) {
        File f = new File(dir);
        if (!f.exists()) {
            LOGGER.info("The target dir {} is not exist, try to create new dir", dir);
            if (!f.mkdirs()) {
                LOGGER.error("Failed to create new dir {}", dir);
                throw SecretpadException.of(SystemErrorCode.UNKNOWN_ERROR, "Failed to create new dir when download.");
            }
        }
    }

    /**
     * Check fileName
     * <p>
     * 校验上传文件名：
     * <ol>
     *     <li>非空；</li>
     *     <li>不包含 {@code /} 或 {@code \}，防止跨目录攻击；</li>
     *     <li>后缀必须为 {@code .csv}。</li>
     * </ol>
     *
     * @param fileName file name
     */
    private void fileNameCheck(String fileName) {
        if (fileName == null) {
            LOGGER.error("The user input fileName {} is empty!", fileName);
            throw SecretpadException.of(DataErrorCode.FILE_NAME_EMPTY);
        }
        if (fileName.contains("/") || fileName.contains("\\")) {
            LOGGER.error("The user input filName {} contains / or \\, which will cause cross dir attack!", fileName);
            throw SecretpadException.of(DataErrorCode.ILLEGAL_PARAMS_ERROR, "file name cannot contains \\ or /");
        }
        String suffixName = fileName.substring(fileName.lastIndexOf('.'));
        if (!SUPPORT_FILE_TYPE.contains(suffixName)) {
            LOGGER.error("The user input fileName {} type {} not support yet.", fileName, suffixName);
            throw SecretpadException.of(DataErrorCode.FILE_TYPE_NOT_SUPPORT, "does not support " + suffixName + " type file.");
        }
    }

    /**
     * Build random file name via random Integer
     * <p>
     * 生成随机上传文件名，规则为：原文件名前缀 + "_" + 随机整数 + 原后缀。
     * 例如 {@code breast.csv} 可能变为 {@code breast_123456789.csv}。
     *
     * @param fileName file name
     * @return random file name
     */
    private String getRandomFileName(String fileName) {
        String prefix = fileName.substring(0, fileName.lastIndexOf('.'));
        String suffixName = fileName.substring(fileName.lastIndexOf('.'));
        String randomFileName = prefix + "_" + RANDOM.nextInt(Integer.MAX_VALUE) + suffixName;
        LOGGER.info("generate random upload file name: {}", randomFileName);
        return randomFileName;
    }

    /**
     * Valid nodeId if contains impermissible char
     * <p>
     * 校验 nodeId 不包含 {@code /} 或 {@code \}，防止构造非法目录路径。
     *
     * @param nodeId target nodeId
     */
    private void nodeIdValidCheck(String nodeId) {
        if (nodeId.contains("/") || nodeId.contains("\\")) {
            LOGGER.error("node id {} contains / or \\, which not allowed.", nodeId);
            throw SecretpadException.of(DataErrorCode.ILLEGAL_PARAMS_ERROR, "node ID cannot contains \\ or /");
        }
    }

    /**
     * Valid relative Uri if illegal
     * <p>
     * 校验 relativeUri：
     * <ol>
     *     <li>非空；</li>
     *     <li>不包含 {@code ..}，防止目录穿越。</li>
     * </ol>
     *
     * @param relativeUri relative Uri
     */
    private void relativeUriValidCheck(String relativeUri) {
        if (relativeUri == null || "".equals(relativeUri)) {
            throw SecretpadException.of(DataErrorCode.ILLEGAL_PARAMS_ERROR,
                    "relative uri is empty!"
            );
        }
        if (relativeUri.contains("..")) {
            throw SecretpadException.of(
                    DataErrorCode.ILLEGAL_PARAMS_ERROR,
                    "relative uri " +
                            relativeUri +
                            " contains invalid character"
            );
        }
    }

    /**
     * 查询默认数据源信息。
     * <p>
     * 当前 SecretPad 仅向用户暴露 Kuscia 内置的 default-data-source（localfs），
     * 路径对应 Kuscia 默认数据目录。其他数据源（OSS/MySQL/ODPS）通过独立的数据源管理接口维护。
     *
     * @return 默认数据源列表，目前仅包含一个元素
     */
    @Override
    public List<DataSourceVO> queryDataSources() {
        List<DataSourceVO> list = new ArrayList<>();
        list.add(DataSourceVO.builder().name(KusciaDataSourceConstants.DEFAULT_DATA_SOURCE).path(KusciaDataSourceConstants.DEFAULT_DATA_SOURCE_PATH).build());
        return list;
    }

    /**
     * 校验当前用户是否有权向指定 nodeId 上传数据。
     * <p>
     * 在 EDGE 平台模式下，用户只能向自己所属（{@code ownerId}）的节点上传数据；
     * 其他平台类型（如 AUTONOMY）不执行此限制。
     *
     * @param nodeId 目标节点 ID
     */
    private void checkDataPermissions(String nodeId) {
        UserContextDTO user = UserContext.getUser();
        if (user.getPlatformType().equals(PlatformTypeEnum.EDGE) && !user.getOwnerId().equals(nodeId)) {
            throw SecretpadException.of(AuthErrorCode.AUTH_FAILED, "no Permissions");
        }
    }
}
