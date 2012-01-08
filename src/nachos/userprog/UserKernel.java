package nachos.userprog;

import java.util.Map;
import java.util.TreeMap;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

/**
 * A kernel that can support multiple user processes.
 */
public class UserKernel extends ThreadedKernel {
	private UserKernelMemory memory;
	
    /**
     * Allocate a new user kernel.
     */
    public UserKernel() {
	super();
	this.processes = new TreeMap<Integer, UserProcess>();
    }

    /**
     * Initialize this kernel. Creates a synchronized console and sets the
     * processor's exception handler.
     */
    public void initialize(String[] args) {
	super.initialize(args);
	this.processManagement = new Lock();
	this.memory = new UserKernelMemory(Machine.processor().getNumPhysPages());
	
	console = new SynchConsole(Machine.console());
	
	Machine.processor().setExceptionHandler(new Runnable() {
		public void run() { exceptionHandler(); }
	    });
    }

    /**
     * Test the console device.
     */	
    public void selfTest() {
	super.selfTest();

/*	System.out.println("Testing the console device. Typed characters");
	System.out.println("will be echoed until q is typed.");

	char c;

	do {
	    c = (char) console.readByte(true);
	    console.writeByte(c);
	}
	while (c != 'q'); */

	System.out.println("");
    }

    /**
     * Returns the current process.
     *
     * @return	the current process, or <tt>null</tt> if no process is current.
     */
    public static UserProcess currentProcess() {
	if (!(KThread.currentThread() instanceof UThread))
	    return null;
	
	return ((UThread) KThread.currentThread()).process;
    }

    /**
     * The exception handler. This handler is called by the processor whenever
     * a user instruction causes a processor exception.
     *
     * <p>
     * When the exception handler is invoked, interrupts are enabled, and the
     * processor's cause register contains an integer identifying the cause of
     * the exception (see the <tt>exceptionZZZ</tt> constants in the
     * <tt>Processor</tt> class). If the exception involves a bad virtual
     * address (e.g. page fault, TLB miss, read-only, bus error, or address
     * error), the processor's BadVAddr register identifies the virtual address
     * that caused the exception.
     */
    public void exceptionHandler() {
	Lib.assertTrue(KThread.currentThread() instanceof UThread);

	UserProcess process = ((UThread) KThread.currentThread()).process;
	int cause = Machine.processor().readRegister(Processor.regCause);
	process.handleException(cause);
    }

    /**
     * Start running user programs, by creating a process and running a shell
     * program in it. The name of the shell program it must run is returned by
     * <tt>Machine.getShellProgramName()</tt>.
     *
     * @see	nachos.machine.Machine#getShellProgramName
     */
    public void run() {
	super.run();

	UserProcess process = startUserProcess(null);
	
	String shellProgram = Machine.getShellProgramName();	
	Lib.assertTrue(process.execute(shellProgram, new String[] { }));

	KThread.currentThread().finish();
    }
    
    public UserKernelMemory getMemoryManager() {
    	return this.memory;
    }

    public UserProcess startUserProcess(UserProcess parent) {
    	Lib.debug(dbgKernel, "Kernel: startUserProcess()");
    	int newPid = assignPid();
    	if (newPid == -1) {
    		return null;
    	}
    	
    	UserProcess process = UserProcess.newUserProcess(newPid, parent);
    	associatePid(process.getPid(), process);
    	return process;
    }
    
    /**
     * Terminate this kernel. Never returns.
     */
    public void terminate() {
	super.terminate();
    }
    
    public int assignPid() {
    	Lib.debug(dbgKernel, "Kernel: assignPid() acquiring lock");
    	processManagement.acquire();
		Lib.debug(dbgKernel, "assignPid(), startPid: " + startPid);
    	if (runningProcesses == Integer.MAX_VALUE) {
    		return -1;
    	}
    	
    	while (processes.containsKey(startPid)) {
    		if (startPid < 0) {
    			startPid = 1;
    		} else {
    			startPid++;
    		}
    	}
    	Lib.debug(dbgKernel, "Kernel: assignPid() releasing lock");
    	processManagement.release();
    	
    	return startPid++;
    }
    
    public boolean associatePid(int pid, UserProcess p) {
    	Integer pidBoxed = pid;

    	Lib.debug(dbgKernel, "Kernel: associatePid() acquiring lock");
    	processManagement.acquire();
    	if (!processes.containsKey(pidBoxed)) {
    		processes.put(pidBoxed, p);
    		runningProcesses++;
        	Lib.debug(dbgKernel, "Kernel: associatePid() releasing lock");
    		processManagement.release();
    		return true;
    	}
    	Lib.debug(dbgKernel, "Kernel: associatePid() releasing lock");
    	processManagement.release();
    	
    	return false;
    }
    
    /**
     * Cleanup process accounting.
     * terminateProcess() will finish the job if necessary.
     */
    public void cleanupProcess(UserProcess p) {
    	processManagement.acquire();
    	p.exit();
    	processes.remove(p.getPid());
    	runningProcesses--;
    	processManagement.release();
    }
    
    public void terminateProcess(UserProcess p) {
    	Lib.debug(dbgKernel, "Kernel: terminateProcess() beginning");
    	this.cleanupProcess(p);
    	processManagement.acquire();
    	if (runningProcesses == 0) {
        	Lib.debug(dbgKernel, "Kernel: terminateProcess() killed the last running process, kernel terminating");
    		Kernel.kernel.terminate();
    	} else {
        	Lib.debug(dbgKernel, "Kernel: terminateProcess() releasing lock and terminating thread");
        	processManagement.release();
    		UThread.finish();
    	}
    	Lib.debug(dbgKernel, "Kernel: terminateProcess() releasing lock");
    	processManagement.release();
    }
    
    public UserProcess getProcessByPid(int pid) {
    	return this.processes.get(pid);
    }
    
    private Lock processManagement;
    private Map<Integer, UserProcess> processes;
    private int startPid = 1;
    private int runningProcesses = 0;

    /** Globally accessible reference to the synchronized console. */
    public static SynchConsole console;
    
    private static final char dbgKernel = 'K';

    // dummy variables to make javac smarter
    private static Coff dummy1 = null;
}
