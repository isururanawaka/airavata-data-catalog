package org.apache.airavata.datacatalog.api.sharing;

import jakarta.annotation.PostConstruct;
import org.apache.airavata.datacatalog.api.DataProduct;
import org.apache.airavata.datacatalog.api.GroupInfo;
import org.apache.airavata.datacatalog.api.Permission;
import org.apache.airavata.datacatalog.api.UserInfo;
import org.apache.airavata.datacatalog.api.exception.SharingException;
import org.apache.airavata.datacatalog.api.model.TenantEntity;
import org.apache.airavata.datacatalog.api.model.UserEntity;
import org.apache.airavata.datacatalog.api.repository.TenantRepository;
import org.apache.custos.clients.CustosClientProvider;
import org.apache.custos.iam.service.FindUsersResponse;
import org.apache.custos.iam.service.UserRepresentation;
import org.apache.custos.sharing.core.Entity;
import org.apache.custos.sharing.core.EntityType;
import org.apache.custos.sharing.core.PermissionType;
import org.apache.custos.sharing.core.exceptions.CustosSharingException;
import org.apache.custos.sharing.core.impl.SharingImpl;
import org.apache.custos.sharing.core.utils.Constants;
import org.apache.custos.user.management.client.UserManagementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component("custosSharingManager")
public class SharingManagerImpl implements SharingManager {

    private static final Logger logger = LoggerFactory.getLogger(SharingManagerImpl.class);

    private static final String DATA_PRODUCT_ENTITY_TYPE_ID = "DATA_PRODUCT";

    @Autowired
    SharingImpl custosSharingImpl;

    @Autowired
    TenantRepository tenantRepository;


    CustosClientProvider custosClientProvider;


    private static final String PUBLIC_ACCESS_GROUP = "public_access_group";

    @PostConstruct
    public void initializeTenants(@Value("${identity.server.hostname}") String hostname,
                                  @Value("${identity.server.port}") int port,
                                  @Value("${identity.server.clientId}") String clientId,
                                  @Value("${identity.server.clientSec}") String clientSec) throws SharingException {


        logger.info("Initializing all tenants");
        List<TenantEntity> tenants = tenantRepository.findAll();
        for (TenantEntity tenant : tenants) {
            this.initialize(tenant.getExternalId());
        }


        logger.info("Initializing Custos client provider");
        custosClientProvider = new CustosClientProvider.Builder()
                .setServerHost(hostname)
                .setServerPort(port)
                .setClientId(clientId)
                .setClientSec(clientSec).build();


    }

    @Override
    public void initialize(String tenantId) throws SharingException {

        logger.info("Initializing tenant {}", tenantId);

        // Create DataProduct entity type
        EntityType entityType = EntityType.newBuilder()
                .setId(DATA_PRODUCT_ENTITY_TYPE_ID)
                .setName("Data Product")
                .build();
        try {
            Optional<EntityType> existingEntityType = custosSharingImpl.getEntityType(tenantId, entityType.getId());
            if (!existingEntityType.isPresent()) {
                custosSharingImpl.createEntityType(tenantId, entityType);
            }
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }

        // Create permission types for all permissions
        for (Permission permission : Permission.values()) {

            PermissionType permissionType = PermissionType.newBuilder()
                    .setId(permission.name())
                    .setName(permission.name())
                    .build();
            try {
                Optional<PermissionType> existingPermissionType = custosSharingImpl.getPermissionType(tenantId,
                        permissionType.getId());
                if (!existingPermissionType.isPresent()) {
                    custosSharingImpl.createPermissionType(permissionType, tenantId);
                }
            } catch (CustosSharingException e) {
                throw new SharingException(e);
            }
        }
    }

    @Override
    public UserEntity resolveUser(UserInfo userInfo) throws SharingException {
        try (UserManagementClient userManagementClient = custosClientProvider.getUserManagementClient()) {
            FindUsersResponse findUsersResponse = userManagementClient.findUser(userInfo.getTenantId(),
                    userInfo.getUserId(), null, null, null, 0, 1);
            if (!findUsersResponse.getUsersList().isEmpty()) {
                UserRepresentation userProfile = findUsersResponse.getUsersList().get(0);
                TenantEntity tenantEntity = new TenantEntity();
                tenantEntity.setExternalId(userInfo.getTenantId());

                UserEntity userEntity = new UserEntity();
                userEntity.setExternalId(userProfile.getId());
                userEntity.setName(userProfile.getUsername());
                userEntity.setTenant(tenantEntity);
                return userEntity;
            } else {
                throw new SharingException("User " + userInfo.getUserId() + " in tenant "
                        + userInfo.getTenantId() + " not found in Identity Sever ");
            }
        } catch (IOException e) {
            throw new SharingException("Error occurred while resolving user " + userInfo.getUserId()
                    + " tenant " + userInfo.getTenantId(), e);
        }

    }

    @Override
    public boolean userHasAccess(UserInfo userInfo, DataProduct dataProduct, Permission permission)
            throws SharingException {
        try {
            return custosSharingImpl.userHasAccess(userInfo.getTenantId(), dataProduct.getDataProductId(),
                    permission.name(),
                    userInfo.getUserId());
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }
    }

    @Override
    public String getDataProductSharingView() {
        return "data_catalog.custos_data_product_sharing_view";
    }

    @Override
    public void grantPermissionToUser(UserInfo userInfo, DataProduct dataProduct, Permission permission,
                                      UserInfo sharedByUser)
            throws SharingException {

        List<String> userIds = new ArrayList<>();
        userIds.add(userInfo.getUserId());
        String sharedByUserId = sharedByUser != null ? sharedByUser.getUserId() : null;
        try {
            createDataProductEntityIfMissing(dataProduct);
            // OWNER permission can't be assigned but it is granted when the data product is
            // created
            if (permission != Permission.OWNER) {
                custosSharingImpl.shareEntity(userInfo.getTenantId(),
                        dataProduct.getDataProductId(), permission.name(), userIds, true, Constants.USER,
                        sharedByUserId);
            }
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }
    }

    @Override
    public void revokePermissionFromUser(UserInfo userInfo, DataProduct dataProduct, Permission permission)
            throws SharingException {

        List<String> userIds = new ArrayList<>();
        userIds.add(userInfo.getUserId());
        try {
            custosSharingImpl.revokePermission(userInfo.getTenantId(),
                    dataProduct.getDataProductId(), permission.name(), userIds);
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }

    }

    @Override
    public void grantPermissionToGroup(GroupInfo groupInfo, DataProduct dataProduct, Permission permission,
                                       UserInfo sharedByUser)
            throws SharingException {

        List<String> userIds = new ArrayList<>();
        userIds.add(groupInfo.getGroupId());
        String sharedByUserId = sharedByUser != null ? sharedByUser.getUserId() : null;
        try {
            custosSharingImpl.shareEntity(groupInfo.getTenantId(),
                    dataProduct.getDataProductId(), permission.name(), userIds, true, Constants.GROUP, sharedByUserId);
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }
    }

    @Override
    public void revokePermissionFromGroup(GroupInfo groupInfo, DataProduct dataProduct, Permission permission)
            throws SharingException {
        List<String> userIds = new ArrayList<>();
        userIds.add(groupInfo.getGroupId());
        try {
            custosSharingImpl.revokePermission(groupInfo.getTenantId(),
                    dataProduct.getDataProductId(), permission.name(), userIds);
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }
    }

    @Override
    public boolean hasPublicAccess(DataProduct dataProduct, Permission permission) throws SharingException {
        try {
            return custosSharingImpl.userHasAccess(dataProduct.getOwner().getTenantId(), dataProduct.getDataProductId(),
                    permission.name(),
                    PUBLIC_ACCESS_GROUP);
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }
    }

    @Override
    public void grantPublicAccess(DataProduct dataProduct, Permission permission) throws SharingException {

        // TODO: create PUBLIC GROUP If not exists
        List<String> userIds = new ArrayList<>();
        userIds.add(PUBLIC_ACCESS_GROUP);
        try {
            custosSharingImpl.shareEntity(dataProduct.getOwner().getTenantId(),
                    dataProduct.getDataProductId(), permission.name(), userIds, true, Constants.GROUP, null);
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }

    }

    @Override
    public void revokePublicAccess(DataProduct dataProduct, Permission permission) throws SharingException {
        List<String> userIds = new ArrayList<>();
        userIds.add(PUBLIC_ACCESS_GROUP);
        try {
            custosSharingImpl.revokePermission(dataProduct.getOwner().getTenantId(),
                    dataProduct.getDataProductId(), permission.name(), userIds);
        } catch (CustosSharingException e) {
            throw new SharingException(e);
        }
    }

    private void createDataProductEntityIfMissing(DataProduct dataProduct) throws CustosSharingException {

        Entity dataProductEntity = Entity.newBuilder()
                .setId(dataProduct.getDataProductId())
                .setParentId(dataProduct.getParentDataProductId())
                .setName(dataProduct.getName())
                .setType(DATA_PRODUCT_ENTITY_TYPE_ID)
                .setOwnerId(dataProduct.getOwner().getUserId())
                .build();

        String tenantId = dataProduct.getOwner().getTenantId();
        if (!custosSharingImpl.isEntityExists(tenantId, dataProduct.getDataProductId())) {
            custosSharingImpl.createEntity(dataProductEntity, tenantId);
        }
    }

}
