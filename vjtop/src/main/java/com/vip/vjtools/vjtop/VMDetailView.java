package com.vip.vjtools.vjtop;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.sun.management.OperatingSystemMXBean;
import com.vip.vjtools.vjtop.VMInfo.VMInfoState;


@SuppressWarnings("restriction")
public class VMDetailView {

	private static final int DEFAULT_WIDTH = 100;
	private static final int MIN_WIDTH = 80;

	// 按线程CPU or 分配内存模式
	volatile public DetailMode mode;
	volatile public int threadLimit = 10;
	volatile public boolean collectingData = true;

	private VMInfo vmInfo;
	private OperatingSystemMXBean operatingSystemMXBean;

	private int width;
	private boolean shouldExit;

	private boolean firstTime = true;

	private Map<Long, Long> lastThreadCpuTotalTimes = new HashMap<Long, Long>();
	private Map<Long, Long> lastThreadSysCpuTotalTimes = new HashMap<Long, Long>();
	private Map<Long, Long> lastThreadMemoryTotalBytes = new HashMap<Long, Long>();

	public VMDetailView(VMInfo vmInfo, DetailMode mode, Integer width) throws Exception {
		this.vmInfo = vmInfo;
		this.mode = mode;
		setWidth(width);
		operatingSystemMXBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
	}

	public void printView() throws Exception {
		long iterationStartTime = System.currentTimeMillis();
		long preCpuTime = operatingSystemMXBean.getProcessCpuTime();

		vmInfo.update();

		if (!checkState()) {
			return;
		}

		// 打印进程级别内容
		printJvmInfo();

		// 打印线程级别内容
		if (mode == DetailMode.memory || mode == DetailMode.totalmemory) {
			printTopMemoryThreads(mode);
		} else {
			printTopCpuThreads(mode);
		}

		// 打印vjtop自身消耗
		long deltaTime = System.currentTimeMillis() - iterationStartTime;
		long deltaCpuTime = (operatingSystemMXBean.getProcessCpuTime() - preCpuTime) / (Utils.NANOS_TO_MILLS);
		System.out.printf(" Cost time: %3dms, CPU time: %3dms%n", deltaTime, deltaCpuTime);
		System.out.print(" Input command (h for help):");
	}

	/**
	 * 打印单条线程的stack strace
	 */
	public void printStack(long tid) throws IOException {
		ThreadInfo info = vmInfo.getThreadMXBean().getThreadInfo(tid, 20);
		if (info == null) {
			System.err.println(" TID not exist:" + tid);
			return;
		}
		StackTraceElement[] trace = info.getStackTrace();
		System.out.println(" " + info.getThreadId() + ":" + info.getThreadName());
		for (StackTraceElement traceElement : trace) {
			System.out.println("\tat " + traceElement);
		}
		System.out.flush();
	}

	public void printAllThreads() throws IOException {
		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();
		ThreadInfo[] threadInfos = vmInfo.getThreadMXBean().getThreadInfo(tids);
		for (ThreadInfo info : threadInfos) {
			System.out.println(" " + info.getThreadId() + "\t:" + info.getThreadName());
		}
		System.out.flush();
	}

	private boolean checkState() {
		if (vmInfo.state == VMInfoState.ATTACHED_UPDATE_ERROR) {
			System.out.println("ERROR: Could not fetch data - Process terminated?");
			return false;
		}

		if (vmInfo.state != VMInfo.VMInfoState.ATTACHED) {
			System.out.println("ERROR: Could not attach to process. ");
			exit();
			return false;
		}

		return true;
	}

	private void printJvmInfo() {
		System.out.printf(" PID: %s - %8tT, JVM: %s, USER: %s, UPTIME: %s%n", vmInfo.pid, new Date(), vmInfo.jvmVersion,
				vmInfo.osUser, Utils.toTimeUnit(vmInfo.upTimeMills.current));

		System.out.printf(" PROCESS: %5.2f%% cpu (%5.2f%% of %d core)", vmInfo.singleCoreCpuLoad * 100,
				vmInfo.cpuLoad * 100, vmInfo.processors);

		if (vmInfo.isLinux) {
			System.out.printf(", %4s rss, %4s swap%n", Utils.toMB(vmInfo.rss), Utils.toMB(vmInfo.swap));

			System.out.printf(" IO: %4s rchar, %4s wchar, %4s read_bytes, %4s write_bytes",
					Utils.toSizeUnit(vmInfo.rchar.delta), Utils.toSizeUnit(vmInfo.wchar.delta),
					Utils.toSizeUnit(vmInfo.readBytes.delta), Utils.toSizeUnit(vmInfo.writeBytes.delta));
		}
		System.out.println();

		System.out.printf(" THREAD: %4d active, %4d daemon, %4d peak, %4d created, CLASS: %d loaded, %d unloaded%n",
				vmInfo.threadActive, vmInfo.threadDaemon, vmInfo.threadPeak, vmInfo.threadStarted, vmInfo.classLoaded,
				vmInfo.classUnLoaded);

		System.out.printf(" HEAP: %s eden, %s sur, %s old%n", Utils.formatUsage(vmInfo.eden),
				Utils.formatUsage(vmInfo.sur), Utils.formatUsage(vmInfo.old));

		System.out.printf(" NON-HEAP: %s %s, %s codeCache", Utils.formatUsage(vmInfo.perm), vmInfo.permGenName,
				Utils.formatUsage(vmInfo.codeCache));
		if (vmInfo.jvmMajorVersion >= 8) {
			System.out.printf(", %s ccs%n", Utils.formatUsage(vmInfo.ccs));
		} else {
			System.out.printf("%n");
		}

		System.out.printf(" OFF-HEAP: %s/%s direct, %s/%s map%n", Utils.toMB(vmInfo.direct.used),
				Utils.toMB(vmInfo.direct.max), Utils.toMB(vmInfo.map.used), Utils.toMB(vmInfo.map.max));

		System.out.printf(" GC: %d/%dms ygc, %d/%dms fgc", vmInfo.ygcCount.delta, vmInfo.ygcTimeMills.delta,
				vmInfo.fullgcCount.delta, vmInfo.fullgcTimeMills.delta);

		if (vmInfo.perfDataSupport) {
			System.out.printf(", SAFE-POINT: %d count, %dms time, %dms syncTime%n", vmInfo.safepointCount.delta,
					vmInfo.safepointTimeMills.delta, vmInfo.safepointSyncTimeMills.delta);
		} else {
			System.out.printf("%n");
		}
	}

	private void printTopCpuThreads(DetailMode mode) throws IOException {
		if (!vmInfo.threadCpuTimeSupported) {
			System.out.printf("%n -Thread CPU telemetries are not available on the monitored jvm/platform-%n");
			return;
		}

		Map<Long, Long> threadCpuTotalTimes = new HashMap<Long, Long>();
		Map<Long, Long> threadCpuDeltaTimes = new HashMap<Long, Long>();
		Map<Long, Long> threadSysCpuTotalTimes = new HashMap<Long, Long>();
		Map<Long, Long> threadSysCpuDeltaTimes = new HashMap<Long, Long>();

		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();

		// 批量获取CPU times，性能大幅提高。
		// 两次获取之间有间隔，在低流量下可能造成负数
		long[] threadCpuTotalTimeArray = vmInfo.getThreadMXBean().getThreadCpuTime(tids);
		long[] threadUserCpuTotalTimeArray = vmInfo.getThreadMXBean().getThreadUserTime(tids);

		long deltaAllThreadCpu = 0;
		long deltaAllThreadSysCpu = 0;

		// 计算本次CPU Time
		// 此算法第一次不会显示任何数据，保证每次显示都只显示区间内数据
		for (int i = 0; i < tids.length; i++) {
			Long tid = tids[i];
			long threadCpuTotalTime = threadCpuTotalTimeArray[i];
			threadCpuTotalTimes.put(tid, threadCpuTotalTime);

			Long lastTime = lastThreadCpuTotalTimes.get(tid);
			if (lastTime != null) {
				long deltaThreadCpuTime = threadCpuTotalTime - lastTime;
				threadCpuDeltaTimes.put(tid, deltaThreadCpuTime);
				deltaAllThreadCpu += deltaThreadCpuTime;
			}
		}

		// 计算本次SYSCPU Time
		for (int i = 0; i < tids.length; i++) {
			Long tid = tids[i];
			// 要处理cpuTime 获取时间有先后，sys本身接近0时，造成sysTime为负数的场景,
			long threadSysCpuTotalTime = Math.max(0, threadCpuTotalTimeArray[i] - threadUserCpuTotalTimeArray[i]);
			threadSysCpuTotalTimes.put(tid, threadSysCpuTotalTime);

			Long lastTime = lastThreadSysCpuTotalTimes.get(tid);
			if (lastTime != null) {
				long deltaThreadSysCpuTime = Math.max(0, threadSysCpuTotalTime - lastTime);
				threadSysCpuDeltaTimes.put(tid, deltaThreadSysCpuTime);
				deltaAllThreadSysCpu += deltaThreadSysCpuTime;
			}
		}

		// 第一次无数据时跳过
		if (lastThreadCpuTotalTimes.isEmpty()) {
			lastThreadCpuTotalTimes = threadCpuTotalTimes;
			lastThreadSysCpuTotalTimes = threadSysCpuTotalTimes;
			printWelcome();
			return;
		}

		collectingData = false;

		// 打印线程view的页头
		String titleFormat = " %6s %-" + getThreadNameWidth() + "s %10s %6s %6s %6s %6s%n";
		String dataFormat = " %6d %-" + getThreadNameWidth() + "s %10s %5.2f%% %5.2f%% %5.2f%% %5.2f%%%n";
		System.out.printf("%n%n" + titleFormat, "TID", "NAME  ", "STATE", "CPU", "SYSCPU", " TOTAL", "TOLSYS");

		// 按不同类型排序,过滤
		long[] topTidArray;
		if (mode == DetailMode.cpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadCpuDeltaTimes, threadLimit);
		} else if (mode == DetailMode.syscpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadSysCpuDeltaTimes, threadLimit);
		} else if (mode == DetailMode.totalcpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadCpuTotalTimes, threadLimit);
		} else if (mode == DetailMode.totalsyscpu) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadSysCpuTotalTimes, threadLimit);
		} else {
			throw new RuntimeException("unkown mode");
		}

		// 获得threadInfo
		ThreadInfo[] threadInfos = vmInfo.getThreadMXBean().getThreadInfo(topTidArray);

		// 打印线程Detail
		for (ThreadInfo info : threadInfos) {
			Long tid = info.getThreadId();
			if (info != null) {
				String threadName = Utils.shortName(info.getThreadName(), getThreadNameWidth(), 20);

				System.out.printf(dataFormat, tid, threadName, Utils.leftStr(info.getThreadState().toString(), 10),
						getThreadCPUUtilization(threadCpuDeltaTimes.get(tid), vmInfo.upTimeMills.delta,
								Utils.NANOS_TO_MILLS),
						getThreadCPUUtilization(threadSysCpuDeltaTimes.get(tid), vmInfo.upTimeMills.delta,
								Utils.NANOS_TO_MILLS),
						getThreadCPUUtilization(threadCpuTotalTimes.get(tid), vmInfo.cpuTimeNanos.last, 1),
						getThreadCPUUtilization(threadSysCpuTotalTimes.get(tid), vmInfo.cpuTimeNanos.last, 1));
			}
		}

		// 打印线程汇总
		double deltaAllThreadCpuLoad = Utils.calcLoad(vmInfo.upTimeMills.delta,
				(deltaAllThreadCpu * 100) / (Utils.NANOS_TO_MILLS * 1D), 1);
		double deltaAllThreadSysCpuLoad = Utils.calcLoad(vmInfo.upTimeMills.delta,
				(deltaAllThreadSysCpu * 100) / (Utils.NANOS_TO_MILLS * 1D), 1);

		System.out.printf("%n Total cpu: %5.2f%% (user=%5.2f%%, sys=%5.2f%%)", deltaAllThreadCpuLoad,
				deltaAllThreadCpuLoad - deltaAllThreadSysCpuLoad, deltaAllThreadSysCpuLoad);

		if (threadCpuTotalTimes.size() > threadLimit) {
			System.out.printf(", top %d threads are shown, order by %s%n", threadLimit, mode.toString().toUpperCase());
		} else {
			System.out.printf(", all %d threads are shown, order by %s%n", threadCpuTotalTimes.size(),
					mode.toString().toUpperCase());
		}

		lastThreadCpuTotalTimes = threadCpuTotalTimes;
		lastThreadSysCpuTotalTimes = threadSysCpuTotalTimes;
	}

	private void printTopMemoryThreads(DetailMode mode) throws IOException {

		if (!vmInfo.threadMemoryAllocatedSupported) {
			System.out.printf(
					"%n -Thread Memory Allocated telemetries are not available on the monitored jvm/platform-%n");
			return;
		}

		long tids[] = vmInfo.getThreadMXBean().getAllThreadIds();

		Map<Long, Long> threadMemoryTotalBytesMap = new HashMap<Long, Long>();
		Map<Long, Long> threadMemoryDeltaBytesMap = new HashMap<Long, Long>();
		long totalDeltaBytes = 0;
		long totalBytes = 0;

		// 批量获取内存分配
		long[] threadMemoryTotalBytesArray = vmInfo.getThreadMXBean().getThreadAllocatedBytes(tids);

		// 此算法第一次不会显示任何数据，保证每次显示都只显示区间内数据
		for (int i = 0; i < tids.length; i++) {
			Long tid = tids[i];
			long threadMemoryTotalBytes = threadMemoryTotalBytesArray[i];
			threadMemoryTotalBytesMap.put(tid, threadMemoryTotalBytes);
			totalBytes += threadMemoryTotalBytes;

			long threadMemoryDeltaBytes = 0;
			Long lastBytes = lastThreadMemoryTotalBytes.get(tid);

			if (lastBytes != null) {
				threadMemoryDeltaBytes = threadMemoryTotalBytes - lastBytes;
				threadMemoryDeltaBytesMap.put(tid, threadMemoryDeltaBytes);
				totalDeltaBytes += threadMemoryDeltaBytes;
			}
		}

		// 第一次无数据跳过
		if (lastThreadMemoryTotalBytes.size() == 0) {
			lastThreadMemoryTotalBytes = threadMemoryTotalBytesMap;
			printWelcome();
			return;
		}

		collectingData = false;

		// 打印线程View的页头
		String titleFormat = " %6s %-" + getThreadNameWidth() + "s %10s %14s %18s%n";
		String dataFormat = " %6d %-" + getThreadNameWidth() + "s %10s %5s/s(%5.2f%%) %10s(%5.2f%%)%n";
		System.out.printf("%n%n" + titleFormat, "TID", "NAME  ", "STATE", "MEMORY", "TOTAL-ALLOCATED");

		// 线程排序
		long[] topTidArray;
		if (mode == DetailMode.memory) {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadMemoryDeltaBytesMap, threadLimit);
		} else {
			topTidArray = Utils.sortAndFilterThreadIdsByValue(threadMemoryTotalBytesMap, threadLimit);
		}

		ThreadInfo[] threadInfos = vmInfo.getThreadMXBean().getThreadInfo(topTidArray);

		// 打印线程Detail
		for (ThreadInfo info : threadInfos) {
			Long tid = info.getThreadId();
			String threadName = Utils.shortName(info.getThreadName(), getThreadNameWidth(), 12);

			System.out.printf(dataFormat, tid, threadName, Utils.leftStr(info.getThreadState().toString(), 10),
					Utils.toSizeUnit((threadMemoryDeltaBytesMap.get(tid) * 1000) / vmInfo.upTimeMills.delta),
					getThreadMemoryUtilization(threadMemoryDeltaBytesMap.get(tid), totalDeltaBytes),
					Utils.toSizeUnit(threadMemoryTotalBytesMap.get(tid)),
					getThreadMemoryUtilization(threadMemoryTotalBytesMap.get(tid), totalBytes));
		}

		// 打印线程汇总信息，这里因为最后单位是精确到秒，所以bytes除以毫秒以后要乘以1000才是按秒统计
		System.out.printf("%n Total memory allocate rate : %5s/s",
				Utils.toSizeUnit((totalDeltaBytes * 1000) / vmInfo.upTimeMills.delta));

		if (threadMemoryTotalBytesMap.size() > threadLimit) {
			System.out.printf(", top %d threads are shown, order by %s%n", threadLimit, mode.toString().toUpperCase());
		} else {
			System.out.printf(", all %d threads are shown, order by %s%n", threadMemoryTotalBytesMap.size(),
					mode.toString().toUpperCase());
		}

		lastThreadMemoryTotalBytes = threadMemoryTotalBytesMap;
	}

	private void printWelcome() {
		if (firstTime) {
			System.out.printf(" VMARGS: %s%n%n", vmInfo.vmArgs);
			firstTime = false;
		}
		System.out.printf("%n Collecting data, please wait ......%n%n");
		collectingData = true;
	}

	private static double getThreadCPUUtilization(Long deltaThreadCpuTime, long totalTime, double factor) {
		if (deltaThreadCpuTime == null) {
			return 0;
		}
		if (totalTime == 0) {
			return 0;
		}
		return deltaThreadCpuTime * 100d / factor / totalTime;// 这里因为最后单位是百分比%，所以cpu time除以total cpu
		// time以后要乘以100，才可以再加上单位%
	}

	private static double getThreadMemoryUtilization(Long threadBytes, long totalBytes) {
		if (threadBytes == null) {
			return 0;
		}
		if (totalBytes == 0) {
			return 0;
		}
		return (threadBytes * 100d) / totalBytes;// 这里因为最后单位是百分比%，所以bytes除以totalBytes以后要乘以100，才可以再加上单位%
	}

	public boolean shouldExit() {
		return shouldExit;
	}

	/**
	 * Requests the disposal of this view - it should be called again.
	 */
	public void exit() {
		shouldExit = true;
	}

	private void setWidth(Integer width) {
		if (width == null) {
			this.width = DEFAULT_WIDTH;
		} else if (width < MIN_WIDTH) {
			this.width = MIN_WIDTH;
		} else {
			this.width = width;
		}
	}

	private int getThreadNameWidth() {
		return this.width - 48;
	}

	public enum DetailMode {
		cpu, totalcpu, syscpu, totalsyscpu, memory, totalmemory;

		public static DetailMode parse(String mode) {
			switch (mode) {
				case "1":
					return cpu;
				case "2":
					return syscpu;
				case "3":
					return totalcpu;
				case "4":
					return totalsyscpu;
				case "5":
					return memory;
				case "6":
					return totalmemory;
				default:
					return null;
			}
		}
	}
}
