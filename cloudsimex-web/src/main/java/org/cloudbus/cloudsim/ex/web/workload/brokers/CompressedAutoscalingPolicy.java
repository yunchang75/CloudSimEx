package org.cloudbus.cloudsim.ex.web.workload.brokers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.ex.IAutoscalingPolicy;
import org.cloudbus.cloudsim.ex.MonitoringBorkerEX;
import org.cloudbus.cloudsim.ex.billing.IVmBillingPolicy;
import org.cloudbus.cloudsim.ex.disk.HddCloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.ex.disk.HddVm;
import org.cloudbus.cloudsim.ex.util.CustomLog;
import org.cloudbus.cloudsim.ex.vm.VMStatus;
import org.cloudbus.cloudsim.ex.web.ILoadBalancer;

/**
 * 
 * An autoscaling policy, which proactively allocates and releases resources for
 * a 3-tier application.
 * 
 * @author nikolay.grozev
 * 
 */
public class CompressedAutoscalingPolicy implements IAutoscalingPolicy {

    private StringBuilder debugSB = new StringBuilder();

    private long appId;
    private double triggerCPU;
    private double triggerRAM;
    private int n;

    public CompressedAutoscalingPolicy(long appId, double triggerCPU, double triggerRAM, int n) {
	super();
	this.appId = appId;
	this.triggerCPU = triggerCPU;
	this.triggerRAM = triggerRAM;
	this.n = n;
    }

    @Override
    public void scale(final MonitoringBorkerEX broker) {
	if (broker instanceof WebBroker) {
	    WebBroker webBroker = (WebBroker) broker;
	    ILoadBalancer loadBalancer = webBroker.getLoadBalancers().get(appId);
	    Set<Integer> usedASServers = webBroker.getUsedASServers();

	    int numOverloaded = 0;
	    int numAS = 0;
	    List<HddVm> freeVms = new ArrayList<>();

	    // Inspect the status of all AS VMs
	    boolean debug = true;// (int) (CloudSim.clock() * 100) % 300 == 0;
	    debugSB.setLength(0);
	    for (HddVm vm : loadBalancer.getAppServers()) {
		if (!EnumSet.of(VMStatus.INITIALISING, VMStatus.RUNNING).contains(vm.getStatus())) {
		    continue;
		}
		numAS++;

		appendDebug(debugSB, vm, debug);
		double vmCPU = vm.getCPUUtil();
		double vmRAM = vm.getRAMUtil();
		if (!usedASServers.contains(vm.getId())) {
		    freeVms.add(vm);
		    appendDebug(debugSB, "[FREE, ", debug);
		} else if (vmCPU >= triggerCPU || vmRAM >= triggerRAM) {
		    numOverloaded++;
		    appendDebug(debugSB, "[OVERLOADED, ", debug);
		} else {
		    appendDebug(debugSB, "[", debug);
		}
		appendFormatDebug(debugSB, debug, "%s] ", vm.getStatus());
		appendFormatDebug(debugSB, debug, "cpu(%.2f) ram(%.2f) cdlts(%d);\t",
			vmCPU, vmRAM, vm.getCloudletScheduler().getCloudletExecList().size());
	    }

	    if (debug) {
		CustomLog.printf("Autoscale-Policy(%s): %s", broker, debugSB);
	    }

	    int numFree = freeVms.size();
	    boolean allOverloaded = numOverloaded + numFree == numAS && numOverloaded > 0;

	    if (numFree <= n) { // Provision more VMs..
		int numVmsToStart = 0;
		if (allOverloaded) {
		    numVmsToStart = n - numFree + 1;
		} else {
		    numVmsToStart = n - numFree;
		}
		// Start numVmsToStart new AS VMs
		startASVms(webBroker, loadBalancer, numVmsToStart);
	    } else { // Release VMs
		int numVmsToStop = 0;
		if (allOverloaded) {
		    numVmsToStop = numFree - n;
		} else {
		    numVmsToStop = numFree - n + 1;
		}

		List<HddVm> toStop = new ArrayList<>();
		Collections.sort(freeVms, new CloudPriceComparator(webBroker.getVMBillingPolicy()));
		for (int i = 0; i < numVmsToStop; i++) {
		    double billTime = webBroker.getVMBillingPolicy().nexChargeTime(freeVms.get(i));
		    int delta = 10;
		    if (freeVms.get(i).getStatus() == VMStatus.RUNNING && billTime - CloudSim.clock() < delta &&
			    toStop.size() < numAS - 1) {
			toStop.add(freeVms.get(i));
		    } else {
			break;
		    }
		}

		if (!toStop.isEmpty()) {
		    CustomLog.printf("Autoscale-Policy(%s) Scale-Down: AS VMs terminated: %s",
			    webBroker.toString(), toStop.toString());
		    webBroker.destroyVMsAfter(toStop, 0);
		    loadBalancer.getAppServers().removeAll(toStop);
		}
	    }
	}
    }

    public static void appendDebug(StringBuilder debugSB, Object o, final boolean debugIt) {
	if (debugIt) {
	    debugSB.append(o.toString());
	}
    }

    public static void appendFormatDebug(StringBuilder debugSB, final boolean debugIt, String format, Object... args) {
	if (debugIt) {
	    debugSB.append(String.format(format, args));
	}
    }

    private void startASVms(WebBroker webBroker, ILoadBalancer loadBalancer, int numVmsToStart) {
	if (numVmsToStart > 0) {
	    List<HddVm> newVMs = new ArrayList<>();
	    for (int i = 0; i < numVmsToStart; i++) {
		HddVm newASServer = loadBalancer.getAppServers().get(0).clone(new HddCloudletSchedulerTimeShared());
		loadBalancer.registerAppServer(newASServer);
		newVMs.add(newASServer);
	    }

	    CustomLog
		    .printf("Autoscale-Policy(%s) Scale-Up: New AS VMs provisioned: %s",
			    webBroker.toString(), newVMs.toString());
	    webBroker.createVmsAfter(newVMs, 0);
	}
    }

    private static class CloudPriceComparator implements Comparator<HddVm> {
	private IVmBillingPolicy policy;

	public CloudPriceComparator(final IVmBillingPolicy policy) {
	    super();
	    this.policy = policy;
	}

	@Override
	public int compare(final HddVm vm1, final HddVm vm2) {
	    return Double.valueOf(policy.nexChargeTime(vm1)).compareTo(policy.nexChargeTime(vm2));
	}

    }

}
