package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see	nachos.vm.VMProcess
 * @see	nachos.network.NetProcess
 */
public class UserProcess {
    /**
     * Allocate a new process.
     */
    public UserProcess() {
    	this.upLock = new Lock();
    	
    } 
    
    /**
     * Allocate and return a new process of the correct class. The class name
     * is specified by the <tt>nachos.conf</tt> key
     * <tt>Kernel.processClassName</tt>.
     *
     * @return	a new process of the correct class.
     */
	public static UserProcess newUserProcess(int pid, UserProcess parent) {
		UserProcess up = (UserProcess) Lib.constructObject(Machine
				.getProcessClassName());
		up.setKernel((UserKernel)UserKernel.kernel);
		up.setPid(pid);
		up.setParent(parent);
		up.fileDescriptors.put(0, UserKernel.console.openForReading());
		up.fileDescriptors.put(1, UserKernel.console.openForWriting());

		return up;
	}

	private void setPid(int pid) {
		this.pid = pid;
	}

	/**
     * Sets a reference to the execution kernel. Probably shouldn't happen
     * more than once.
     * 
     * @param kernel The kernel executing this process
     */
    public void setKernel(UserKernel kernel) {
    	if (this.kernel == null)
    		this.kernel = kernel;
    }

    /**
     * Execute the specified program with the specified arguments. Attempts to
     * load the program, and then forks a thread to run it.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
	if (!loadElf(name, args)) {
		this.kernel.cleanupProcess(this);
	    return false;
	}
	
	new UThread(this).setName(name).fork();

	return true;
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
	Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read
     * at most <tt>maxLength + 1</tt> bytes from the specified address, search
     * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
     * without including the null terminator. If no null terminator is found,
     * returns <tt>null</tt>.
     *
     * @param	vaddr	the starting virtual address of the null-terminated
     *			string.
     * @param	maxLength	the maximum number of characters in the string,
     *				not including the null terminator.
     * @return	the string read, or <tt>null</tt> if no null terminator was
     *		found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
	Lib.assertTrue(maxLength >= 0);

	byte[] bytes = new byte[maxLength+1];

	int bytesRead = readVirtualMemory(vaddr, bytes);

	for (int length=0; length<bytesRead; length++) {
	    if (bytes[length] == 0)
		return new String(bytes, 0, length);
	}

	return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
	return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @param	offset	the first byte to write in the array.
     * @param	length	the number of bytes to transfer from virtual memory to
     *			the array.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data, int offset,
				 int length) {
	Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

	byte[] memory = Machine.processor().getMemory();
	
/*	// for now, just assume that virtual addresses equal physical addresses
	if (vaddr < 0 || vaddr >= memory.length)
	    return 0;

	int amount = Math.min(length, memory.length-vaddr);
	System.arraycopy(memory, vaddr, data, offset, amount); */
	
	int amount = length;
	
	while (length > 0) {
		// negatives might be ugly here
		int virtualPage = Math.abs(vaddr / pageSize);
		if (virtualPage >= pageTable.length) {
			// we're trying to read beyond the physical memory allocated
			break;
		}
		TranslationEntry page = vpnToPage(virtualPage);
		int paddr_start = (vaddr % pageSize) + (page.ppn * pageSize);
		int paddr_end = (page.ppn + 1) * pageSize;
		
		int readLength;
		if ((paddr_start + length) > paddr_end)
			readLength = paddr_end - paddr_start;
		else
			readLength = length;
		
		System.arraycopy(memory, paddr_start, data, offset, readLength);
		
		vaddr += readLength;
		offset += readLength;
		length -= readLength;
	}

	return (amount - length);
    }

    /**
     * Transfer all data from the specified array to this process's virtual
     * memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
	return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @param	offset	the first byte to transfer from the array.
     * @param	length	the number of bytes to transfer from the array to
     *			virtual memory.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset,
				  int length) {
	Lib.assertTrue(offset >= 0 && length >= 0 && offset+length <= data.length);

	byte[] memory = Machine.processor().getMemory();
	
	int amount = length;
	
	while (length > 0) {
		// negatives might be ugly here
		int virtualPage = Math.abs(vaddr / pageSize);
		if (virtualPage >= pageTable.length) {
			// we're trying to write beyond the physical memory allocated
			break;
		}
		
		TranslationEntry page = vpnToPage(virtualPage);
		int paddr_start = (vaddr % pageSize) + (page.ppn * pageSize);
		int paddr_end = (page.ppn + 1) * pageSize;
		
		int writeLength;
		if ((paddr_start + length) > paddr_end)
			writeLength = paddr_end - paddr_start;
		else
			writeLength = length;
		
		System.arraycopy(data, offset, memory, paddr_start, writeLength);
		
		vaddr += writeLength;
		offset += writeLength;
		length -= writeLength;
	}

	return (amount - length);
    }

    /**
     * Load the executable with the specified name into this process, and
     * prepare to pass it the specified arguments. Opens the executable, reads
     * its header information, and copies sections and arguments into this
     * process's virtual memory.
     *
     * This is the ELF binary loader, which replaces the COFF binary loader.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the executable was successfully loaded.
     */
    private boolean loadElf(String name, String[] args) {
	Lib.debug(dbgProcess, "UserProcess.loadElf(\"" + name + "\")");
	
	upLock.acquire();
	OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
	if (executable == null) {
	    Lib.debug(dbgProcess, "\topen failed");
	    upLock.release();
	    return false;
	}

	try {
	    elf = new Elf(executable);
	}
	catch (EOFException e) {
	    executable.close();
	    Lib.debug(dbgProcess, "\tELF load failed");
	    upLock.release();
	    return false;
	}

	// make sure the sections are contiguous and start at page 0
	numPages = 0;
		for (int s=0; s<elf.getNumSections(); s++) {
			ElfSection section = elf.getSection(s);
			if (section.loadable() && section.getFirstVPN() != numPages) {
				elf.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
                upLock.release();
				return false;
			}
			// numPages is 0 for an unloadable section
			// this way we just sort of pretend it doesn't exist
			numPages += section.getNumPages();
		}

	// make sure the argv array will fit in one page
	byte[][] argv = new byte[args.length][];
	int argsSize = 0;
	for (int i=0; i<args.length; i++) {
	    argv[i] = args[i].getBytes();
	    // 4 bytes for argv[] pointer; then string plus one for null byte
	    argsSize += 4 + argv[i].length + 1;
	}
	if (argsSize > pageSize) {
	    elf.close();
	    Lib.debug(dbgProcess, "\targuments too long");
	    upLock.release();
	    return false;
	}

	// program counter initially points at the program entry point
	initialPC = elf.getEntryPoint();

	// next comes the stack; stack pointer initially points to top of it
	numPages += stackPages;
	initialSP = numPages*pageSize;

	// and finally reserve 1 page for arguments
	numPages++;
	
	if (!initializePages(numPages)) {
		elf.close();
		Lib.debug(dbgProcess, "\tinsufficient physical memory");
		upLock.release();
		return false;
	}
		
	if (!loadElfSections()) {
		upLock.release();
		return false;
	}

	// store arguments in last page
	int entryOffset = (numPages-1)*pageSize;
	int stringOffset = entryOffset + args.length*4;

	this.argc = args.length;
	this.argv = entryOffset;
	
	for (int i=0; i<argv.length; i++) {
	    byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
	    Lib.assertTrue(writeVirtualMemory(entryOffset,stringOffsetBytes) == 4);
	    entryOffset += 4;
	    Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
		       argv[i].length);
	    stringOffset += argv[i].length;
	    Lib.assertTrue(writeVirtualMemory(stringOffset,new byte[] { 0 }) == 1);
	    stringOffset += 1;
	}
	upLock.release();

	return true;
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into
     * memory. If this returns successfully, the process will definitely be
     * run (this is the last step in process initialization that can fail).
     *
     * @return	<tt>true</tt> if the sections were successfully loaded.
     */
	protected boolean loadElfSections() {
		// load sections
		for (int s = 0; s < elf.getNumSections(); s++) {
			ElfSection section = elf.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getNumPages() + " pages)");

			for (int i = 0; i < section.getNumPages(); i++) {
				int vpn = section.getFirstVPN() + i;

				TranslationEntry page = vpnToPage(vpn);
				section.loadPage(i, page.ppn);
				if (section.isReadOnly()) {
					page.readOnly = true;
				}
			}
		}

		return true;
	}

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
    	UserKernelMemory memory = kernel.getMemoryManager();
    	
    	upLock.acquire();
		if (pageTable != null) {
			for (TranslationEntry te : pageTable) {
				memory.freePage(this, te.ppn);
				te = null;
			}
		}
    	upLock.release();
    }    

    /**
     * Initialize the processor's registers in preparation for running the
     * program loaded into this process. Set the PC register to point at the
     * start function, set the stack pointer register to point at the top of
     * the stack, set the A0 and A1 registers to argc and argv, respectively,
     * and initialize all other registers to 0.
     */
    public void initRegisters() {
	Processor processor = Machine.processor();

	// by default, everything's 0
	for (int i=0; i<processor.numUserRegisters; i++)
	    processor.writeRegister(i, 0);

	// initialize PC and SP according
	processor.writeRegister(Processor.regPC, initialPC);
	processor.writeRegister(Processor.regSP, initialSP);

	// initialize the first two argument registers to argc and argv
	processor.writeRegister(Processor.regA0, argc);
	processor.writeRegister(Processor.regA1, argv);
    }

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
     * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
     * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td></tr>
     * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
     * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
     * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
     * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
     * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
     * </table>
     * 
     * @param	syscall	the syscall number.
     * @param	a0	the first syscall argument.
     * @param	a1	the second syscall argument.
     * @param	a2	the third syscall argument.
     * @param	a3	the fourth syscall argument.
     * @return	the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {

    	UserProcessSyscalls call = UserProcessSyscalls.getSyscall(syscall);
    	return call.handle(this, a0, a1, a2, a3);
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param	cause	the user exception that occurred.
     */
    public void handleException(int cause) {
	Processor processor = Machine.processor();

	switch (cause) {
	case Processor.exceptionSyscall:
	    int result = handleSyscall(processor.readRegister(Processor.regV0),
				       processor.readRegister(Processor.regA0),
				       processor.readRegister(Processor.regA1),
				       processor.readRegister(Processor.regA2),
				       processor.readRegister(Processor.regA3)
				       );
	    processor.writeRegister(Processor.regV0, result);
	    processor.advancePC();
	    break;
	
	case Processor.exceptionReadOnly:
		Lib.debug(dbgProcess, "PID " + this.getPid() + ": Writing to a read-only virtual page");
		this.abnormalTermination = true;
		this.kernel.terminateProcess(this);
	case Processor.exceptionBusError:
		Lib.debug(dbgProcess, "PID " + this.getPid() + ": Bus error (invalid virtual address)");
		this.abnormalTermination = true;
		this.kernel.terminateProcess(this);
	case Processor.exceptionAddressError:
		Lib.debug(dbgProcess, "PID " + this.getPid() + ": Misaligned virtual address");
		this.abnormalTermination = true;
		this.kernel.terminateProcess(this);
	case Processor.exceptionIllegalInstruction:
		Lib.debug(dbgProcess, "PID " + this.getPid() + ": Illegal processor instruction");
		this.abnormalTermination = true;
		this.kernel.terminateProcess(this);
				       
	default:
	    Lib.debug(dbgProcess, "Unexpected exception: " +
		      Processor.exceptionNames[cause]);
	    Lib.assertNotReached("Unexpected exception");
	}
    }
    
    /**
     * Accesses the program's page table in order to collect a list of the page numbers
     * owned by this process.
     * 
     * @return List of page numbers
     */
    public List<Integer> getPhysicalPages() {
    	List<Integer> ints = new ArrayList<Integer>();
    	
    	for (TranslationEntry te : pageTable) {
    		ints.add(te.ppn);
    	}
    	
    	return ints;
    }
    
    public TranslationEntry vpnToPage(int vpn) {
    	for (TranslationEntry te : pageTable) {
    		if (te.vpn == vpn) {
    			return te;
    		}
    	}
    	
    	return null;
    }
    
    /**
     * Initialize the page table.
     * Only load() calls this method, and it pre-allocates the lock.
     * 
     * @param numPages Number of physical pages of memory to allocate
     * @return True if successful; false otherwise
     */
    private boolean initializePages(int numPages) {
    	List<Integer> newPages = kernel.getMemoryManager().allocate(this, numPages);
    	if (newPages != null && newPages.size() > 0) {
    		this.pageTable = new TranslationEntry[numPages];
    		for (int i = 0; i < numPages; i++) {
    			this.pageTable[i] = new TranslationEntry(i, newPages.get(i), true, false, false, false);
    		}
    		return true;
    	} else {
    		return false;
    	}
    }
    
	public int getPid() {
		return this.pid;
	}
	
	public void exit() {
		Lib.debug(dbgProcess, "PID " + this.getPid() + " acquiring lock");
		upLock.acquire();
		Lib.debug(dbgProcess, "PID " + this.getPid() + " closing files");
		Integer[] fds = fileDescriptors.keySet().toArray(new Integer[0]);
		for (Integer fd : fds) {
			Lib.debug(dbgProcess, "PID " + this.getPid() + " attempting to close fd " + fd);
			UserProcessSyscalls.syscallClose(this, fd);
		}
		Lib.debug(dbgProcess, "PID " + this.getPid() + " releasing lock");
		upLock.release();

		// unloadSections will re-lock on its own
		this.unloadSections();
	}
	
    private void setParent(UserProcess parent) {
		this.parent = parent;
	}
	
	public UserProcess getParent() {
		return this.parent;
	}
	
	public boolean exitedCleanly() {
		return !abnormalTermination;
	}
	
	public int FileDescriptor(OpenFile file)
	{
		int fd = 0;
		if(!fileDescriptors.containsValue(file))
		{
			fdc++;
			fileDescriptors.put(fdc, file);
			fd = fdc;
		}
		else
		{
			for(int i = 0; i <= fdc; i++)
			{
				if(fileDescriptors.get(i) == file)
				{
					fd = i;
					break;
				}
			}
		}
		return fd;
	}

    /** The program being run by this process. */
    protected Elf elf;

    /** This process's page table. */
    protected TranslationEntry[] pageTable;
    /** The number of contiguous pages occupied by the program. */
    protected int numPages;

    /** The number of pages in the program's stack. */
    protected final int stackPages = 8;
    
    private int initialPC, initialSP;
    private int argc, argv;
    
    /** Globally unique process identifier. */
    private int pid;
    
    /** Reference to the kernel so we can perform memory management. */
    private UserKernel kernel = null;
    
    private List<UserProcess> children = new ArrayList<UserProcess>();
    private UserProcess parent;
	
    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
    
    public HashMap<Integer, OpenFile> fileDescriptors = new HashMap<Integer, OpenFile>();
    public int exitedChild = 0;

    /** 
     * If making modifications to the information contained within, you must acquire
     * this lock.
     */
    public Lock upLock;
    public Semaphore joining = new Semaphore(0);
    public int joinedto = 0;
    public int exitcode = 0;
    private int fdc = 2;
    
    
    private boolean abnormalTermination = false;
}
