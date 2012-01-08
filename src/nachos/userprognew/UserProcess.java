package nachos.userprognew;
import java.util.*;

import nachos.machine.*;
import nachos.threads.*;

import java.io.EOFException;
import java.nio.ByteBuffer;

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
	protected static final int numVirtualPages = 64;
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
		childExitStatuses = new HashMap<Integer, Integer>();
		children = new HashMap<Integer, UserProcess>();

		pageTable = new TranslationEntry[numVirtualPages];

		for (int i=0; i<numVirtualPages; i++)
			pageTable[i] = new TranslationEntry(i,i, true,false,false,false);

		descriptorTable.add(0, UserKernel.console.openForReading());
		descriptorTable.add(1, UserKernel.console.openForWriting());

		pidLock.acquire();
		pid = currentPID;
		currentPID++;
		pidLock.release();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name
	 * is specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 *
	 * @return	a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		return (UserProcess)Lib.constructObject(Machine.getProcessClassName());
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
		if (!loadElf(name, args)){
			return false;
		}

		processThread = new UThread(this);
		processThread.setName(name);
		processThread.fork();

		pidLock.acquire();
		runningProcesses++;
		pidLock.release();

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

		if (vaddr < 0 || vaddr >= memory.length)
			return 0;

		// These calculations are in case the address+length interval
		// crosses a virtual page boundary. If they do, we need to look
		// up two addresses.

		int currentVaddr = vaddr;
		int startVpn = vaddr/pageSize;
		int endVpn = (vaddr+length)/pageSize;
		int initialOffset = vaddr % pageSize;
		int bytesCopied = offset;

		for(int i=startVpn; i<=endVpn; i++){
			int ppn = pageTable[i].ppn;
			pageTable[i].used = true;
			
			int paddr = ppn*pageSize + initialOffset;
			
			// This calculation finds the amount of bytes that can be copied
			// before reaching a virtual page boundary.
			int bytesToCopy = Math.min(
					(((currentVaddr/pageSize)+1)*pageSize) - currentVaddr,
					length);

			System.arraycopy(memory, paddr, data, bytesCopied, bytesToCopy);
			
			// Only used once on the initial copy, after we cross a page boundary
			// then the next read always starts at offset 0;
			initialOffset = 0;
			bytesCopied += bytesToCopy; 
			currentVaddr += bytesToCopy;
			length -= bytesToCopy;
		}

		return bytesCopied - offset;
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

		if (vaddr < 0 || vaddr/pageSize >= numVirtualPages)
			return 0;

		// These calculations are in case the address+length interval
		// crosses a virtual page boundary. If they do, we need to look
		// up two addresses.

		int currentVaddr = vaddr;
		int startVpn = vaddr/pageSize;
		int endVpn = (vaddr+length)/pageSize;
		int initialOffset = vaddr % pageSize;
		int bytesCopied = offset;

		for(int i=startVpn; i<=endVpn; i++){
			int ppn = pageTable[i].ppn;
			pageTable[i].used = true;
			pageTable[i].dirty = true;

			int paddr = ppn*pageSize + initialOffset;
			
			// This calculation finds the amount of bytes that can be copied
			// before reaching a virtual page boundary.
			int bytesToCopy = Math.min(
					(((currentVaddr/pageSize)+1)*pageSize) - currentVaddr,
					length);

			System.arraycopy(data, bytesCopied, memory, paddr, bytesToCopy);
			
			// Only used once on the initial copy, after we cross a page boundary
			// then the next read always starts at offset 0;
			initialOffset = 0;
			bytesCopied += bytesToCopy;
			currentVaddr += bytesToCopy;
			length -= bytesToCopy;
		}

		return bytesCopied - offset;
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

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			elf = new Elf(executable);
		}catch (EOFException e) {
			executable.close();
			e.printStackTrace();
			Lib.debug(dbgProcess, "\tELF load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s=0; s<elf.getNumSections(); s++) {
			ElfSection section = elf.getSection(s);
			if (section.loadable() && section.getFirstVPN() != numPages) {
				elf.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
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
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = elf.getEntryPoint();	

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages*pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadElfSections())
			return false;

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

		return true;
	}
	
	/**
	 * Allocates memory for this process, and loads the ELF sections into
	 * memory. If this returns successfully, the process will definitely be
	 * run (this is the last step in process initialization that can fail).
	 *
	 * @return	<tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadElfSections() {
		if (numPages > Machine.processor().getNumPhysPages()) {
			elf.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}

		// Set up page table by requesting free memory from kernel.
		for(int i=0; i<numPages; i++){
			// Get a free page from the kernel.
			if(((UserKernel)Kernel.kernel).numFreePages() == 0){
				return false;
			}

			int ppn = ((UserKernel)Kernel.kernel).getFreePage();
			pageTable[i].ppn = ppn;
			pageTable[i].used = true;
		}

        int debugPageCount = 0;

		// load sections
		for (int s=0; s<elf.getNumSections(); s++) {
			ElfSection section = elf.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getNumPages() + " pages)");

			for (int i=0; i<section.getNumPages(); i++) {
				int vpn = section.getFirstVPN()+i;

				pageTable[vpn].readOnly = section.isReadOnly();
				if(pageTable[vpn].vpn != vpn){
					return false;
				}
				section.loadPage(i, pageTable[vpn].ppn);
                ++debugPageCount;
			}
		}

        /* DEBUG: The ELF Program LOAD section can tell us the image size
         * Let's validate it against what we actually allocated.
         * The memory image size from the ELF PHT is a minimum image size; it won't
         * round up to pageSize boundaries, so the ELF reported image size will be
         * less than or equal to the size we calculate.
         */
        ElfProgram p = this.elf.getLoadProgramEntry();
        long size = p.getProgramSize();
        Lib.assertTrue(size <= debugPageCount * pageSize,
                String.format("LOAD size: %d, calculated: %d\n", size, debugPageCount * pageSize));

		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for(TranslationEntry e : pageTable){
			if(e.used){
				e.used = false;

				// Put each page's physical page back into the free list.
				//System.out.println("freeing: " + e.ppn);
				((UserKernel)Kernel.kernel).free(e.ppn);
			}
		}
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
	 * Handle the halt() system call. Should only be called by the root process.
	 * Returns -1 otherwise. 
	 */
	protected int handleHalt() {
		if(pid == 0){
			Machine.halt();
			Lib.assertNotReached("Machine.halt() did not halt machine!");
			return 0;
		}else{
			return -1;
		}
	}

	protected int handleExit(int a0) {
		// Store our exit status in case the parent needs it for join()
		if(parent != null){
			HashMap<Integer, Integer> statuses = parent.getChildExitStatuses();
			statuses.put(pid, a0);
		}

		unloadSections();

        if (elf != null) {
            elf.close();
            elf = null;
        }

		for(OpenFile file : descriptorTable){
			if(file != null){
				file.close();
			}
		}

		pidLock.acquire();
		runningProcesses--;
		pidLock.release();

		if(runningProcesses == 0){
			Kernel.kernel.terminate();
			Lib.assertNotReached("Terminate() failed to terminate the kernel.");
		}else{
			UThread.finish();
		}

		return a0;
	}

	protected byte[] reverseBytes(byte[] b){
		byte[] reversedBytes = new byte[b.length];

		for(int i=0;i<b.length; i++){
			reversedBytes[i] = b[b.length - (i+1)];
		}

		return reversedBytes;
	}

	/**
	 * Executes a new process as a child of this process. Reads the process
	 * binary from a given file location and passes it a set of arguments.
	 * @param a0 the memory address of the filename string.
	 * @param a1 the number of arguments to pass to the new program.
	 * @param a2 the memory address of an array of arguments given to exec.
	 * @return the pid of the child process spawned by exec, or -1 if an
	 * error occurred.
	 */
	protected int handleExec(int a0, int a1, int a2){
		String filename = readVirtualMemoryString(a0, 256);
		if(filename == null || a1 < 0){
			return -1;
		}else{
			UserProcess p = newUserProcess();
			p.parent = this;

			if(p == null){
				return -1;
			}

			// Nachos integers have opposite endianness from Java ints.
			byte[] argPointerBytes = new byte[4];
			readVirtualMemory(a2, argPointerBytes);
			byte[] argPointerReverse = reverseBytes(argPointerBytes);

			int argPointer = ByteBuffer.wrap(argPointerReverse).getInt();
			String[] args = new String[a1];

			for(int i=0; i<a1; i++){
				String arg = readVirtualMemoryString(argPointer, 512);
				if(arg == null){
					return -1;
				}
				argPointer += arg.length()+1;
				args[i] = arg;
			}

			int newPid = p.getPid();
			children.put(newPid, p);

			boolean successful = p.execute(filename, args);
			if(!successful){
				return -1;
			}else{
				return newPid;
			}
		}
	}

	/**
	 * Joins to a child thread. Can only be called by its parent.
	 * @param a0 the pid of the child to join to.
	 * @param a1 a memory address to write the child's status to on exit.
	 * @return 1 if the child exited normally, 0 if the child exited
	 * due to an unexpected error message, and -1 if the pid does not reference
	 * a child of this process.
	 */
	protected int handleJoin(int a0, int a1){
		if(children.size() < 1){
			return -1;
		}

		UserProcess child = children.get(a0);
		if(child == null){
			return -1;
		}

		// Wait for the child thread to terminate.
		child.processThread.join();

		Integer status = childExitStatuses.get(a0);
		if(status == null){
			status = 1;
		}

		// Convert the status to a byte array and write it to the address
		// given to join();
		byte[] statusBytes = reverseBytes(
				ByteBuffer.allocate(4).putInt(status).array());

		writeVirtualMemory(a1, statusBytes);

		// Prevent anyone from joining to it again;
		children.remove(a0);
		childExitStatuses.remove(a0);

		if(status >= 0){
			return 1;
		}else{
			return 0;
		}
	}

	/**
	 * Open a new file, creating it if it does not exist.
	 * @param a0 the virtual memory address of the filename string.
	 * @return the new file descriptor on success, or -1 on failure.
	 */
	protected int handleCreate(int a0){
		String filename = readVirtualMemoryString(a0, 256);
		if(filename == null){
			return -1;
		}else{
			OpenFile createdFile = UserKernel.fileSystem.open(filename, true);
			if(createdFile == null){
				return -1;
			}

			descriptorTable.add(createdFile);
			return descriptorTable.indexOf(createdFile);
		}
	}

	/**
	 * Opens a file at a given location in the file system, only if
	 * the file is already existing.
	 * @param a0 a memory address of a string containing the path to
	 * the file to open.
	 * @return the file descriptor of the newly opened file, or -1 if
	 * an error occurred.
	 */
	protected int handleOpen(int a0){
		String filename = readVirtualMemoryString(a0, 256);
		if(filename == null){
			return -1;
		}else{
			OpenFile openedFile = UserKernel.fileSystem.open(filename, false);
			if(openedFile == null){
				return -1;
			}

			descriptorTable.add(openedFile);
			return descriptorTable.indexOf(openedFile);
		}
	}

	/**
	 * Reads data from a file descriptor into a buffer until a given
	 * amount of bytes have been read.
	 * @param a0 the file descriptor to read from.
	 * @param a1 a memory address of a buffer to write the data to.
	 * @param a2 the maximum number of bytes to read from the descriptor.
	 * @return the number of bytes read from the descriptor, or -1 if an error
	 * occurred.
	 */
	protected int handleRead(int a0, int a1, int a2){
		if(a0 >= 0 && a0 < descriptorTable.size() &&
				descriptorTable.get(a0) != null && a2 > 0){
			OpenFile file = descriptorTable.get(a0);

			byte[] data = new byte[a2];
			int bytesRead = file.read(data, 0, a2);

			if(bytesRead < 0){
				return -1;
			}else if(bytesRead == 0){
				return 0;
			}

			byte[] dataToWrite = new byte[bytesRead];
			System.arraycopy(data, 0, dataToWrite, 0, bytesRead);

			int bytesWritten = writeVirtualMemory(a1, dataToWrite);

			if(bytesWritten < 0){
				return -1;
			}

			return bytesRead;
		}else{
			return -1;
		}
	}

	/**
	 * Writes a buffer of a given length to a file descriptor. The argument
	 * a1 must point to a string of < 256 bytes in length, or it will
	 * be truncated to 256 bytes.
	 * @param a0 the file descriptor to write to.
	 * @param a1 a memory address of a buffer of data to write.
	 * @param a2 the length of the data to write.
	 * @return the number of bytes written, or -1 if an error
	 * occurred.
	 */
	protected int handleWrite(int a0, int a1, int a2){
		if(a0 >= 0 && a0 < descriptorTable.size() &&
				descriptorTable.get(a0) != null && a2 > 0){
			OpenFile file = descriptorTable.get(a0);
			String data = readVirtualMemoryString(a1, 256);

			if(data == null){
				return -1;
			}else{
				int bytesWritten = file.write(data.getBytes(), 0, a2);
				return bytesWritten;
			}
		}else{
			return -1;
		}
	}

	/**
	 * Closes an open file descriptor. Close will return an error if
	 * there is no open file descriptor at the given index.
	 * @param a0 the file descriptor to close
	 * @return 0 on success, -1 on failure.
	 */
	protected int handleClose(int a0){
		if(a0 < descriptorTable.size() && a0 >= 0 &&
				descriptorTable.get(a0) != null){
			descriptorTable.get(a0).close();
			descriptorTable.remove(a0);
			return 0;
		}

		return -1;
	}

	protected int handleUnlink(int a0){
		String filename = readVirtualMemoryString(a0, 256);
		if(filename == null){
			return -1;
		}else{
			if(!UserKernel.fileSystem.remove(filename)){
				return -1;
			}else{
				return 0;
			}
		}
	}

	private static final int
	syscallHalt = 0,
	syscallExit = 1,
	syscallExec = 2,
	syscallJoin = 3,
	syscallCreate = 4,
	syscallOpen = 5,
	syscallRead = 6,
	syscallWrite = 7,
	syscallClose = 8,
	syscallUnlink = 9;

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
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			return handleExit(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);

		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
		Lib.assertNotReached("Unknown system call!");
		}
		return 0;
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

		default:
			Lib.debug(dbgProcess, "Unexpected exception: " +
					Processor.exceptionNames[cause]);
		// Kill the process and free its resources.
		// handleExit() does this for us already.
		handleExit(-1);
		Lib.assertNotReached("Unexpected exception");
		}
	}

	public int getPid() {
		return pid;
	}

	/** The program being run by this process. */
	protected Elf elf;

	private int pid;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;
	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	private int initialPC, initialSP;
	private int argc, argv;

	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';

	protected ArrayList<OpenFile> descriptorTable = new ArrayList<OpenFile>(16);

	public static int currentPID = 0;
	public static int runningProcesses = 0;
	public static Lock pidLock = new Lock();

	private HashMap<Integer, UserProcess> children;
	protected HashMap<Integer, Integer> childExitStatuses;
		

	protected UThread processThread;
	protected UserProcess parent;

	public HashMap<Integer, Integer> getChildExitStatuses() {
		return childExitStatuses;
	}

	public void setChildExitStatuses(HashMap<Integer, Integer> childExitStatuses) {
		this.childExitStatuses = childExitStatuses;
	}
}
