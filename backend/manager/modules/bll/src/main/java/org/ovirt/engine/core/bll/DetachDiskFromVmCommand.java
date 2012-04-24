package org.ovirt.engine.core.bll;

import org.ovirt.engine.core.bll.utils.VmDeviceUtils;
import org.ovirt.engine.core.common.action.AttachDettachVmDiskParameters;
import org.ovirt.engine.core.common.businessentities.Disk;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.VmDevice;
import org.ovirt.engine.core.common.businessentities.VmDeviceId;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.VdcBllMessages;

public class DetachDiskFromVmCommand<T extends AttachDettachVmDiskParameters> extends AbstractDiskVmCommand<T> {

    private static final long serialVersionUID = -4424772106319982885L;
    private Disk disk;
    private VmDevice vmDevice;

    public DetachDiskFromVmCommand(T parameters) {
        super(parameters);
    }

    @Override
    protected boolean canDoAction() {
        boolean retValue = isVmExist();
        if (retValue && getVm().getstatus() != VMStatus.Up && getVm().getstatus() != VMStatus.Down) {
            retValue = false;
            addCanDoActionMessage(VdcBllMessages.ACTION_TYPE_FAILED_VM_STATUS_ILLEGAL);
        }

        if (retValue) {
            disk = getDiskDao().get((Guid)getParameters().getEntityId());
            retValue = isDiskExist(disk);
        }
        if (retValue) {
            vmDevice = getVmDeviceDao().get(new VmDeviceId(disk.getId(), getVmId()));
            if (vmDevice == null) {
                retValue = false;
                addCanDoActionMessage(VdcBllMessages.ACTION_TYPE_FAILED_DISK_ALREADY_DETACHED);
            }
        }
        if (retValue && Boolean.TRUE.equals(getParameters().isPlugUnPlug())
                && getVm().getstatus() != VMStatus.Down) {
            retValue = isHotPlugSupported() && isOSSupportingHotPlug()
                            && isInterfaceSupportedForPlugUnPlug(disk);
        }

        if (retValue && Boolean.FALSE.equals(getParameters().isPlugUnPlug())
                && getVm().getstatus() != VMStatus.Down) {
            addCanDoActionMessage(VdcBllMessages.ACTION_TYPE_FAILED_VM_IS_NOT_DOWN);
            retValue = false;
        }

        return retValue;
    }

    @Override
    protected void setActionMessageParameters() {
        addCanDoActionMessage(VdcBllMessages.VAR__ACTION__DETACH_ACTION_TO);
        addCanDoActionMessage(VdcBllMessages.VAR__TYPE__VM_DISK);
    }

    @Override
    protected void ExecuteVmCommand() {
        if (Boolean.TRUE.equals(getParameters().isPlugUnPlug() && getVm().getstatus() != VMStatus.Down)) {
            performPlugCommnad(VDSCommandType.HotUnPlugDisk, disk, vmDevice);
        }
        getVmDeviceDao().remove(vmDevice.getId());
        // update cached image
        VmHandler.updateDisksFromDb(getVm());
        // update vm device boot order
        VmDeviceUtils.updateBootOrderInVmDevice(getVm().getStaticData());
        setSucceeded(true);
    }
}
