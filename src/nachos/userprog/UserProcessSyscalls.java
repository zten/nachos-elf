package nachos.userprog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.machine.OpenFile;
import nachos.threads.Semaphore;

/**
 * Dispatch for system calls.
 * 
 * The process initiating the call needs to be passed in via the handle method.
 * 
 * @author Christopher Childs, Eric Muehlberg
 *
 */
public enum UserProcessSyscalls {
	notFound(-1) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3) {
		    Lib.assertNotReached("Unknown system call!");
		    return 0;
		}
	}, halt(0) {
		@Override
		/**
		 * handler for halt(), which technically takes no arguments.
		 * invokes the syscallHalt() method.
		 */
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			return syscallHalt(p);
		}
	}, exit(1) {
		@Override
		/**
		 * 
		 */
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			return syscallExit(p, a0);
		}
		
	}, exec(2) {
		@Override
		/**
		 * 
		 */
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			Lib.debug(dbgProcess, "PID: " + p.getPid() + " exec handler entered");
			byte argv_addresses[] = null;
			String filename = p.readVirtualMemoryString(a0, 255);
			argv_addresses = new byte[a1 * 4];
			String argv[] = new String[a1];
			p.readVirtualMemory(a2, argv_addresses);
			ByteBuffer addr_buffer = ByteBuffer.wrap(argv_addresses).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < a1; i++) {
				int addr = addr_buffer.getInt(i * 4);
				argv[i] = p.readVirtualMemoryString(addr, 255);
			}
			
			Lib.debug(dbgProcess, "PID: " + p.getPid() + " exec " + filename + " argc: " + a1);
			for (int i = 0; i < argv.length; i++) {
				Lib.debug(dbgProcess, "PID: " + p.getPid() + " argv[" + i + "]: " + argv[i]);
			}
			
			return syscallExec(p, filename, a1, argv);
		}
	}, join(3) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{	
			UserProcess child = syscallJoin(p, a0);
			if(child == null)
				return -1;
			if(child.exitedCleanly())
			{
				ByteBuffer tmp = ByteBuffer.allocate(4).putInt(child.exitcode);
				tmp = tmp.order(ByteOrder.LITTLE_ENDIAN);
				p.writeVirtualMemory(a1, tmp.array());
				return 1;
			}
			return 0;
		}
	}, creat(4) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			String filename = p.readVirtualMemoryString(a0, 255);
			return syscallCreat(p, filename);
		}
	}, open(5) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			String filename = p.readVirtualMemoryString(a0, 255);
			return syscallOpen(p, filename);
		}
	}, read(6) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			byte buffer[] = syscallRead(p, a0, a2);
			if (buffer == null) {
				Lib.debug(dbgProcessExtra, "PID: " + p.getPid() + " read -- fd " + a0 + " buffer addr " + a1 + " count " + a3 + ": buffer null");
				return 0;
			}
			// write buffer to memory
			p.writeVirtualMemory(a1, buffer);
			return buffer.length;
		}
	}, write(7) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			byte[] buffer = new byte[a2];
			Lib.debug(dbgProcessExtra, "PID: " + p.getPid() + " write -- fd " + a0 + " buffer addr " + a1 + " count " + a3);
			int count = p.readVirtualMemory(a1, buffer);
			return syscallWrite(p, a0, Arrays.copyOfRange(buffer, 0, count));
		}
	}, close(8) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			return syscallClose(p, a0);
		}
	}, unlink(9) {
		@Override
		public int handle(UserProcess p, int a0, int a1, int a2, int a3)
		{
			String filename = p.readVirtualMemoryString(a0, 255);
			return syscallUnlink(p, filename);
		}
	};
	
	private UserProcessSyscalls(int id) {
		this.syscallId = id;
	}
	
	private int syscallId;
	
	public int getId() {
		return this.syscallId;
	}
	
	public abstract int handle(UserProcess p, int a0, int a1, int a2, int a3);

    private static final char dbgProcess = 'a';
    private static final char dbgProcessExtra = 'A';
	private static final Map<Integer, UserProcessSyscalls> idToSyscall = new HashMap<Integer, UserProcessSyscalls>();
	static {
		for (UserProcessSyscalls call : UserProcessSyscalls.values()) {
			idToSyscall.put(call.getId(), call);
		}
	}
	
	public static UserProcessSyscalls getSyscall(int id) {
		Integer idBoxed = id;
		if (idToSyscall.containsKey(idBoxed)) {
			return idToSyscall.get(idBoxed);
		} else {
			Lib.debug(dbgProcess, "Unknown syscall " + id);
			return UserProcessSyscalls.notFound;
		}
	}
	
	/* System call implementations */
	
	public static int syscallHalt(UserProcess p)
	{
		if (p.getPid() == 1) {
			Machine.halt();
		
			Lib.assertNotReached("Machine.halt() did not halt machine!");
		}
		
		return 0;
	
	}
	
	public static int syscallExit(UserProcess p, int status)
	{
		UserProcess parent = p.getParent();
		if(parent != null && (parent.joinedto == p.getPid()))
		{
			parent.exitedChild = status;
			parent.joining.V();
		}
		
		p.exitcode = status;
		
		((UserKernel)UserKernel.kernel).terminateProcess(p);
		
		return status;

	}
	
	public static int syscallExec(UserProcess p, String filename, int argc, String[] argv)
	{
		Lib.debug(dbgProcess, "PID: " + p.getPid() + " inside syscall");
		UserProcess newup = ((UserKernel)UserKernel.kernel).startUserProcess(p);
		if (newup.execute(filename, argv)) {
			return newup.getPid();
		} else {
			return -1;
		}
	}
	
	public static UserProcess syscallJoin(UserProcess p, int pid)
	{	
		UserProcess child = ((UserKernel)UserKernel.kernel).getProcessByPid(pid);
		if (child == null)
		{
			return null;
		}
		else
		{
			if (p != child.getParent())
			{
				return null;
			}
			
			if(p == child.getParent() && p.joinedto == 0)
			{	
				p.upLock.acquire();
				p.joinedto = pid;
				p.upLock.release();
				p.joining.P();
				p.joinedto = 0;
			}
			else
				return null;
			
			return child;
		}
	}
	
	public static int syscallCreat(UserProcess p, String filename)
	{
		//Attempt to open file
		OpenFile file = UserKernel.fileSystem.open(filename, false);
		
		//If file does not exist
		if(file == null)
		{
			//Create file
			file = UserKernel.fileSystem.open(filename, true);
				//If file still does not exist return error
				if(file == null)
					return -1;
		}
		
		//Map the file to the file descriptor
		return p.FileDescriptor(file);
		}
	
	public static int syscallOpen(UserProcess p, String filename)
	{
		//Attempt to open file
		OpenFile file = UserKernel.fileSystem.open(filename, false);
		//If file does not exist return error
		if(file == null)
			return -1;
		
		//Return the file descriptor
		return p.FileDescriptor(file);
	}
	
	public static byte[] syscallRead(UserProcess p, int fd, int count)
	{
		//Check to see if file exists
		if(!p.fileDescriptors.containsKey(fd))
			return null;
		
		//Get file from map
		OpenFile file = (OpenFile)p.fileDescriptors.get(fd);
		
		byte buffer[] = new byte[count];
		

		int scount = file.read(buffer, 0, count);
		
		//Return new buffer
		return Arrays.copyOfRange(buffer, 0, scount);
	}
	
	public static int syscallWrite(UserProcess p, int fd, byte[] buffer)
	{	
		//Check to see if file exists
		if(!p.fileDescriptors.containsKey(fd))
			return -1;
		
		//Get file from map
		OpenFile file = (OpenFile)p.fileDescriptors.get(fd);
		
		//While i is less than the desired count
		int scount = file.write(buffer, 0, buffer.length);

		//Return number of bytes read
		return scount;
	
	}
	
	public static int syscallClose(UserProcess p, int fd)
	{		
		OpenFile file;
		
		if(p.fileDescriptors.containsKey(fd))
		{

			file = (OpenFile)p.fileDescriptors.get(fd);
			file.close();
		}
		else if(!p.fileDescriptors.containsKey(fd))
			return -1;
		
		if(p.fileDescriptors.containsKey(fd))
			p.fileDescriptors.remove(fd);
		else if(!p.fileDescriptors.containsKey(fd))
			return -1;
			
		return 0;
	}
	
	public static int syscallUnlink(UserProcess p, String filename)
	{	
		//Remove the file from the system
		return (UserKernel.fileSystem.remove(filename) ? 0 : 1);
	}
}
