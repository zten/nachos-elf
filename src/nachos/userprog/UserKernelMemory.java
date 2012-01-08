package nachos.userprog;

import java.util.ArrayList;
import java.util.List;

import nachos.machine.Lib;
import nachos.threads.Lock;

/**
 * User kernel memory manager.
 * 
 * This should probably be a singleton. Might change to enum later and change initialization routine
 * to not be the constructor.
 * 
 * @author Christopher Childs, Eric Muehlberg
 *
 */

public class UserKernelMemory {
	static char dbgMemory = 'E';
	static char dbgMemoryPages = 'P';
	private Lock memoryLock;
	private int freePages;
	private List<MemoryPage> pages;
	
	public UserKernelMemory(int totalPages) {
		this.pages = new ArrayList<MemoryPage>();
		this.freePages = totalPages;
		for (int i = 0; i < freePages; i++) {
			MemoryPage page = new MemoryPage(i);
			pages.add(page);
		}
		this.memoryLock = new Lock();
	}
	
	private class MemoryPage {
		private int pageNumber;
		private UserProcess process;
		
		public MemoryPage(int pageNumber) {
			this.pageNumber = pageNumber;
		}
		
		/**
		 * 
		 * @return The process that has allocated this MemoryPage
		 */
		public UserProcess getProcess() {
			return this.process;
		}
		
		/**
		 * 
		 * @return The physical page number of this MemoryPage
		 */
		public int getPpn() {
			return this.pageNumber;
		}

		/**
		 * Allocates this page to a UserProcess, if it is not already allocated.
		 * 
		 * @param p The UserProcess to which this page shall be allocated
		 */
		public void allocate(UserProcess p) {
			if (this.process == null) {
				this.process = p;
			} else {
				Lib.debug(dbgMemory, "Tried to allocate memory page " + this.pageNumber + 
						" to process " + p + " when it is already owned by " + this.process);
				
			}
		}

		/**
		 * Frees this memory page, if it is not unallocated
		 */
		public void free() {
			if (this.process != null) {
				this.process = null;
			} else {
				Lib.debug(dbgMemory, "Tried to free memory page " + this.pageNumber +
						" despite it not being previously allocated.");
			}
		}
		
		/**
		 * 
		 * @return True if this page is free; false otherwise
		 */
		public boolean isFree() {
			if (this.process == null) {
				return true;
			} else {
				return false;
			}
		}
		
	}
	
	/**
	 * Allocates memory to a user process
	 * @param p The process requesting the allocation
	 * @param numPages The number of pages requested
	 * @return A list of integers with the _physical_ page numbers assigned, which
	 * 			will be used by the page table to set up the vpn->ppn relationship.
	 */
	public List<Integer> allocate(UserProcess p, int numPages) {
		Lib.debug(dbgMemory, "PID: " + p.getPid() + " allocating memory, acquiring lock");
		memoryLock.acquire();
		Lib.debug(dbgMemory, "PID: " + p.getPid() + " requesting " + numPages + ", " + this.freePages + " pages free");
		if (numPages > this.freePages) {
			Lib.debug(dbgMemory, "PID: " + p.getPid() + " requesting more pages than free memory; freeing lock");
			memoryLock.release();
			return null;
		}
		
		int allocatedCount = 0;
		List<Integer> allocated = new ArrayList<Integer>(numPages);
		for (MemoryPage mp : pages) {
			Lib.debug(dbgMemoryPages, "MM: page " + mp.pageNumber + " status: " + mp.isFree());
			if (mp.isFree() && allocatedCount < numPages) {
				allocated.add(mp.getPpn());
				mp.allocate(p);
				allocatedCount++;
			}
		}
		
		this.freePages -= numPages;
		Lib.debug(dbgMemory, "PID: " + p.getPid() + " allocated memory, freeing lock, " + freePages + " pages now free");
		memoryLock.release();
		
		return allocated;

	}
	
	/**
	 * Frees a page, first ensuring that it is owned by the process attempting to free it.
	 * 
	 * @param p The process attempting to free an allocation
	 * @param page The _physical_ page to free.
	 */
	
	public void freePage(UserProcess p, int page) {
		try {
			memoryLock.acquire();
			MemoryPage mp = pages.get(page);
			if (mp.getProcess() != p) {
				Lib.debug(dbgMemory, "Process " + p + " attempted to free page " + page + ", which isn't owned by them.");
			} else {
				mp.free();
				this.freePages++;
			}
			memoryLock.release();
		} catch (IndexOutOfBoundsException ie) {
			Lib.debug(dbgMemory, "Process " + p + " attempted to free page " + page + ", which isn't a valid page.");
		}
	}
	
	/**
	 * Frees process memory allocations by requesting the physical pages in the page table. This is
	 * more polite than the other free method.
	 * 
	 * @param p The process that has its memory being freed by this call
	 */
	public void freeProcessMemory(UserProcess p) {
		List<Integer> physicalPages = p.getPhysicalPages();
		
		for (Integer i : physicalPages) {
			freePage(p, i);
		}
	}
	
	/**
	 * Scans the memory pages and frees all allocations. Looks at every page to do this.
	 * 
	 * @param p The process that has its memory being freed by this call
	 */
	public void forceFreeProcessMemory(UserProcess p) {
		for (MemoryPage mp : pages) {
			if (mp.getProcess() == p) {
				memoryLock.acquire();
				if (mp.getProcess() == p) {
					mp.free();
					this.freePages++;
				}
				memoryLock.release();
			}
		}
	}
	
}
