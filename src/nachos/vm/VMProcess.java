package nachos.vm;

import nachos.machine.*;
import nachos.userprognew.*;
import nachos.vm.VMKernel.PageId;

import java.util.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
        vmk = (VMKernel)Kernel.kernel;
		
		// By default, all pages are invalid.
		for (int i=0; i<numVirtualPages; i++)
			pageTable[i].valid = false;
	}
    
	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		super.saveState();
	}
    
	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		// Invalidate all TLB entries;
		for(int i=0; i < Machine.processor().getTLBSize(); i++){
			Lib.debug(dbgVM, "Invalidating TLB on context switch.");
			TranslationEntry entry = Machine.processor().readTLBEntry(i);
			entry.valid = false;
			Machine.processor().writeTLBEntry(i, entry);
		}
		
		syncPageTable();
	}
    
	/**
	 * Called when the process is context switched in. Synchs the process
	 * pagetable with the global one so that read/writeVirtualMemory calls
	 * can proceed as they would normally in the UserProcess class.
	 */
	private void syncPageTable(){
		for(TranslationEntry e : pageTable){
			TranslationEntry f = vmk.lookupAddress(super.getPid(), e.vpn);
			if(f == null || f.valid == false){
				e.valid = false;
			}else if(f != null){
				f.valid = true;
			}
		}
	}
    
    
	/**
	 * Given a virtual address and a number of bytes to read/write, this
	 * will set the used bits for all pages in that range inside
	 * the kernel's pagetable.
	 * @param vaddr the address to read/write from.
	 * @param length the number of bytes to read/write.
	 */
	protected void setUsedBits(int vaddr, int length){
		// Manage the used bit in the global pagetable.
		for(int i=vaddr/pageSize; i<=(vaddr+length)/pageSize; i++){
			TranslationEntry entry = vmk.lookupAddress(super.getPid(), i); 
			entry.used = true;
		}
	}
    
	/**
	 * Given a virtual address and a number of bytes to write, this
	 * will set both the used and dirty bits for all pages in that
	 * range inside the kernel's pagetable.
	 * @param vaddr the address to write to.
	 * @param length the number of bytes to write.
	 */
	protected void setUsedAndDirtyBits(int vaddr, int length){
		setUsedBits(vaddr, length);
		// Manage the dirty bit in the global pagetable.
		for(int i=vaddr/pageSize; i<=(vaddr+length)/pageSize; i++){
			TranslationEntry entry = vmk.lookupAddress(super.getPid(), i); 
			entry.dirty = true;
		}
	}    
    
	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged. In our case the process initially invalidates all pages
	 * in the local pagetable. This will allow the lazy loading to proceed
	 * normally. LoadSections() doesn't have to actually do anything, so it
	 * always returns true.
	 *
	 * @return true always, since the process is loaded lazily.
	 */
	protected boolean loadSections() {
		return true;
	}
    
	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 * First we free any physical pages the process has used, then we
	 * un-map all of our pages from the global pagetable.
	 */
	protected void unloadSections() {
		// Collect a list of pages we need to free.
		// We need to collect them first then free, since you can't
		// modify a hashmap while iterating over it's contents.
		ArrayList<Integer> toBeFreed = new ArrayList<Integer>();
		for(Map.Entry<PageId, TranslationEntry> e :
			vmk.invertedPageTable.entrySet()){
			if(e.getKey().pid == super.getPid() &&
               e.getValue().valid){
				toBeFreed.add(e.getValue().ppn);
			}
		}
		
		// Free the collected pages.
		for(Integer i : toBeFreed){
			vmk.free(i);
		}
		
		// Remove all of this process' page mappings from the kernel.
		ArrayList<PageId> toBeRemoved = new ArrayList<PageId>();
		for(PageId id : vmk.invertedPageTable.keySet()){
			if(id.pid == super.getPid()){
				toBeRemoved.add(id);
			}
		}
        
		for(PageId id : toBeRemoved){
			vmk.invertedPageTable.remove(id);
		}
	}

    
        /**
	 * Since the read/writeVirtualMemory calls rely on the process pagetable,
	 * we need to check if they would cause a pagefault and handle this beforehand.
	 * Once the pagefault(s) have been taken care of, the calls may proceed as normal
	 * since the process' pagetable will have been updated.
	 * @param vaddr the address of the read/write.
	 * @param length the number of bytes to read/write.
	 */
	protected void checkAddresses(int vaddr, int length){
		for(int i=vaddr/pageSize; i<=(vaddr+length)/pageSize; i++){
			TranslationEntry page = vmk.lookupAddress(super.getPid(), i);
			if(page == null || page.valid == false){
				handlePageFault(i);
			}
		}
	}
    
	/**
	 * When we read or write memory using syscalls, the page needs to be
	 * pinned so that it won't be swapped out during the read. These functions
	 * wrap the base class's functions and manage the pinned pages.
	 */
	@Override
	protected int handleRead(int a0, int a1, int a2) {
                checkAddresses(a1, a2);
                TranslationEntry t = vmk.lookupAddress(currentPID, a1 / pageSize);
                vmk.pinPage(t.ppn);
                int retval = super.handleRead(a0, a1, a2);
                vmk.unPinPage(t.ppn);
                return retval;
	}
	
	/**
	 * When we read or write memory using syscalls, the page needs to be
	 * pinned so that it won't be swapped out during the read. These functions
	 * wrap the base class's functions and manage the pinned pages.
	 */
	@Override
	protected int handleWrite(int a0, int a1, int a2) {
                checkAddresses(a1, a2);
                TranslationEntry t = vmk.lookupAddress(currentPID, a1 / pageSize);
                vmk.pinPage(t.ppn);
                int retval = super.handleWrite(a0, a1, a2);
                vmk.unPinPage(t.ppn);
                return retval;
	}
    
	/**
	 * Wraps the base class's readVirtualMemory function to check for pagefaults
	 * and manage kernel used/dirty bits.
	 */
	@Override
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
                checkAddresses(vaddr, length);
                setUsedBits(vaddr, length);
                return super.readVirtualMemory(vaddr, data, offset, length);
	}
    
	/**
	 * Wraps the base class's readVirtualMemory function to check for pagefualts
	 * and manage kernel used/dirty bits.
	 */
	@Override
	public int readVirtualMemory(int vaddr, byte[] data) {
                checkAddresses(vaddr, data.length);
                setUsedBits(vaddr, data.length);
                 return super.readVirtualMemory(vaddr, data);
	}
    
	/**
	 * Wraps the base class's readVirtualMemory function to check for pagefaults
	 * and manage kernel used/dirty bits.
	 */
	@Override
	public String readVirtualMemoryString(int vaddr, int maxLength) {
                checkAddresses(vaddr, maxLength);
                String retval = super.readVirtualMemoryString(vaddr, maxLength);
                setUsedBits(vaddr, retval.length() + 1);
                return retval;
	}
    
	/**
	 * Wraps the base class's readVirtualMemory function to check for pagefaults
	 * and manage kernel used/dirty bits.
	 */
	@Override
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
                checkAddresses(vaddr, length);
                setUsedAndDirtyBits(vaddr, length);
                return super.writeVirtualMemory(vaddr, data, offset, length);
	}
    
	/**
	 * Wraps the base class's readVirtualMemory function to check for pagefaults
	 * and manage kernel used/dirty bits.
	 */
	@Override
	public int writeVirtualMemory(int vaddr, byte[] data) {
                checkAddresses(vaddr, data.length);
                setUsedAndDirtyBits(vaddr, data.length);
                return super.writeVirtualMemory(vaddr, data);
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
		switch (cause) {
                // Handle a TLB miss (and possible pagefault).
            case Processor.exceptionTLBMiss:
                Lib.debug(dbgVM, "TLB Miss");
                int vpn = Machine.processor().readRegister(Processor.regBadVAddr);
            	VMKernel kern = this.vmk;
                if(vpn <= Machine.processor().getTLBSize())
                {
                	//Find proper page
                }
                else
                {
                	//swap correct page into memory
                	kern.swapInPage(VMKernel.currentProcess().getPid(), vpn);
                }
                
                break;
            default:
                super.handleException(cause);
                break;
		}
	}
    
	/**
	 * Process-local function for handling pagefaults caused by this process.
	 * It handles getting a new page from the kernel (which will swap if needed),
	 * and setting up the local pagetable with the new page. It will also manage
	 * the lazy loading of executable pages if the page falls within the virtual
	 * page range of the coff file's sections.
	 * @param vpn the virtual page containing the address that caused a pagefault.
	 * The entire page will be pulled from disk.
	 */
	private void handlePageFault(int vpn){
		if(vpn > pageTable.length || vpn < 0){
			handleExit(-1);
		}
        
		boolean executablePage = false;
        
		if(vmk.isPageInSwap(super.getPid(), vpn)){
			vmk.swapInPage(super.getPid(), vpn);
			TranslationEntry e = vmk.lookupAddress(super.getPid(), vpn);
			pageTable[vpn] = e;
			return;
		}
        
		// If the page was not in swap, this means we have a stack page or
		// non-loaded executable page. First, we check to see if this is
		// an executable page.
		CoffSection section = null;
/*		int j = 0;
		for(int i=0; i < coff.getNumSections() && !executablePage; i++){
			section = coff.getSection(i);
			for(j=0; j < section.getLength(); j++){
				// This is the page we need to load since the vpns match.
				if(section.getFirstVPN() + j == vpn){
					executablePage = true;
					break;
				}
			}
		}*/
        
		// getFreePage() will automatically swap out a page from physical
		// memory to give us this new page if necessary. The page will be
		// swappable if it is not an executable page or if it is a read/write
		// executable page.
		//
		// It will be read-only only when the page is
		// a read-only executable page.
		TranslationEntry newPage = vmk.getFreePage(super.getPid(), vpn,
                                                   !executablePage || !section.isReadOnly(),
                                                   section != null && section.isReadOnly());
        
		// Copy the new page into our local pagetable for the process.
		pageTable[vpn] = newPage;
        
		// If it was an executable page, load it into memory.
/*		if(executablePage){
			Lib.debug(dbgProcess, "\t" + processThread.getName() + 
                      " initializing " + section.getName()
                      + " section (Page " + (j+1) + " of "
                      + section.getLength() + " pages)");
			
			section.loadPage(j, newPage.ppn);
			return;
		}*/
	}
    
	/**
	 * Used to access the kernel for VM-management functions.
	 */
	VMKernel vmk;
    
	private static final int pageSize = Processor.pageSize;
	private static final char dbgProcess = 'a';
	private static final char dbgVM = 'v';
}
