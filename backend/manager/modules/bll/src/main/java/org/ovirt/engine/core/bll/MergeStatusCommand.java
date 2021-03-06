package org.ovirt.engine.core.bll;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.MergeParameters;
import org.ovirt.engine.core.common.action.MergeStatusReturnValue;
import org.ovirt.engine.core.common.action.RemoveSnapshotSingleDiskParameters;
import org.ovirt.engine.core.common.action.RemoveSnapshotSingleDiskStep;
import org.ovirt.engine.core.common.businessentities.StoragePool;
import org.ovirt.engine.core.common.businessentities.StoragePoolStatus;
import org.ovirt.engine.core.common.businessentities.VmBlockJobType;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.utils.VmDeviceType;
import org.ovirt.engine.core.common.vdscommands.ReconcileVolumeChainVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.CommandStatus;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.SnapshotDao;
import org.ovirt.engine.core.dao.StoragePoolDao;
import org.ovirt.engine.core.dao.VmDynamicDao;
import org.ovirt.engine.core.vdsbroker.monitoring.FullListAdapter;
import org.ovirt.engine.core.vdsbroker.vdsbroker.VdsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@InternalCommandAttribute
@NonTransactiveCommandAttribute
public class MergeStatusCommand<T extends MergeParameters>
        extends CommandBase<T> {
    private static final Logger log = LoggerFactory.getLogger(MergeStatusCommand.class);

    @Inject
    private StoragePoolDao storagePoolDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private VmDynamicDao vmDynamicDao;
    @Inject
    private FullListAdapter fullListAdapter;

    public MergeStatusCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected void executeCommand() {
        attemptResolution();
    }

    public void attemptResolution() {
        Set<Guid> images;
        if (vmDynamicDao.get(getParameters().getVmId()).getStatus().isNotRunning()) {
            StoragePool pool = storagePoolDao.get(getParameters().getStoragePoolId());
            if (pool.getSpmVdsId() == null || pool.getStatus() != StoragePoolStatus.Up) {
                log.info("VM down, waiting on SPM election to resolve Live Merge");
                setSucceeded(true);
                return;
            } else {
                log.error("VM is not running, proceeding with Live Merge recovery");
                images = getVolumeChainFromRecovery();
            }
        } else {
            images = getVolumeChain();
        }
        if (images == null || images.isEmpty()) {
            log.error("Failed to retrieve images list of VM {}. Retrying ...", getParameters().getVmId());
            setCommandStatus(CommandStatus.SUCCEEDED);
            setSucceeded(true);
            // As this command is executed only during live merge flow, the following casting is safe.
            ((RemoveSnapshotSingleDiskParameters) getParameters().getParentParameters()).
                    setNextCommandStep(RemoveSnapshotSingleDiskStep.MERGE_STATUS);
            return;
        }

        Guid topImage = getParameters().getTopImage().getImageId();
        if (images.contains(topImage)) {
            // If the top volume is found in qemu chain, it means that the top volume wasn't deleted,
            // thus we have to fail live merge, reporting that the top volume is still in the chain.
            log.error("Failed to live merge. Top volume {} is still in qemu chain {}", topImage, images);
            setCommandStatus(CommandStatus.FAILED);
            return;
        }

        if (!images.contains(getParameters().getBaseImage().getImageId())) {
            // If the base image isn't found in qemu chain, it means that the image was already deleted.
            // In this case, we will not allow PULL merge but rather ask the user to check if the parent
            // snapshot contains illegal volume(s). If so, that snapshot must be deleted before deleting
            // other snapshots
            addCustomValue("SnapshotName", snapshotDao.get(getParameters().getBaseImage().getSnapshotId()).getDescription());
            addCustomValue("BaseVolumeId", getParameters().getBaseImage().getImageId().toString());
            auditLog(this, AuditLogType.USER_REMOVE_SNAPSHOT_FINISHED_FAILURE_BASE_IMAGE_NOT_FOUND);
            setCommandStatus(CommandStatus.FAILED);
            return;
        }

        log.info("Successfully removed volume {} from the chain", topImage);

        // For now, only COMMIT type is supported
        log.info("Volume merge type '{}'", VmBlockJobType.COMMIT.name());

        MergeStatusReturnValue returnValue = new MergeStatusReturnValue(VmBlockJobType.COMMIT,
                Collections.singleton(topImage));
        getReturnValue().setActionReturnValue(returnValue);
        setSucceeded(true);
        persistCommand(getParameters().getParentCommand(), true);
        setCommandStatus(CommandStatus.SUCCEEDED);
    }

    private Set<Guid> getVolumeChain() {
        Map[] vms = null;
        try {
            vms = getVms();
        } catch (EngineException e) {
            log.error("Failed to retrieve images list of VM {}. Retrying ...", getParameters().getVmId(), e);
        }

        if (vms == null || vms.length == 0) {
            log.error("Failed to retrieve VM information");
            return null;
        }

        Map vm = vms[0];
        if (vm == null || vm.get(VdsProperties.vm_guid) == null) {
            log.error("Received incomplete VM information");
            return null;
        }

        Guid vmId = new Guid((String) vm.get(VdsProperties.vm_guid));
        if (!vmId.equals(getParameters().getVmId())) {
            log.error("Invalid VM returned when querying status: expected '{}', got '{}'",
                    getParameters().getVmId(), vmId);
            return null;
        }

        Set<Guid> images = new HashSet<>();
        DiskImage activeDiskImage = getParameters().getActiveImage();
        for (Object o : (Object[]) vm.get(VdsProperties.Devices)) {
            Map device = (Map<String, Object>) o;
            if (VmDeviceType.DISK.getName().equals(device.get(VdsProperties.Device))
                    && activeDiskImage.getId().equals(Guid.createGuidFromString(
                    (String) device.get(VdsProperties.ImageId)))) {
                Object[] volumeChain = (Object[]) device.get(VdsProperties.VolumeChain);
                for (Object v : volumeChain) {
                    Map<String, Object> volume = (Map<String, Object>) v;
                    images.add(Guid.createGuidFromString((String) volume.get(VdsProperties.VolumeId)));
                }
                break;
            }
        }
        return images;
    }

    private Map[] getVms() {
        return (Map[]) fullListAdapter.getVmFullList(
                getParameters().getVdsId(),
                Collections.singletonList(getParameters().getVmId()))
                .getReturnValue();
    }

    private Set<Guid> getVolumeChainFromRecovery() {
        ReconcileVolumeChainVDSCommandParameters parameters =
                new ReconcileVolumeChainVDSCommandParameters(
                        getParameters().getStoragePoolId(),
                        getParameters().getStorageDomainId(),
                        getParameters().getImageGroupId(),
                        getParameters().getImageId()
                );

        try {
            VDSReturnValue vdsReturnValue = runVdsCommand(VDSCommandType.ReconcileVolumeChain,
                    parameters);
            if (!vdsReturnValue.getSucceeded()) {
                log.error("Unable to retrieve volume list during Live Merge recovery");
                return null;
            }
            return new HashSet<>((List<Guid>) vdsReturnValue.getReturnValue());
        } catch (EngineException e) {
            log.error("Unable to retrieve volume list during Live Merge recovery", e);
            return null;
        }
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.singletonList(new PermissionSubject(getParameters().getStorageDomainId(),
                VdcObjectType.Storage,
                getActionType().getActionGroup()));
    }
}
