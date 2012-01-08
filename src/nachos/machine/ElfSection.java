package nachos.machine;

import nachos.security.Privilege;

import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes a section of an ELF32 binary
 * 
 * @author Christopher Childs
 * 
 */
public class ElfSection {
	/** 4 bytes, offset into section header table for section name */
	private long sh_name;
	private String sectionName;
	/**
	 * 4 bytes, categorizes section's contents and semantics -- see
	 * {@link ElfSectionType}
	 */
	private long sh_type;
	private ElfSectionType type;
	/** 4 bytes, flags describing the section */
	private long sh_flags;
	private EnumSet<ElfSectionFlag> flags;
	/**
	 * 4 bytes, if memory resident, gives the address of the first byte in
	 * memory where this section should reside.
	 */
	private long sh_addr;
	/** 4 bytes, offset from beginning of file to beginning of section contents */
	private long sh_offset;
	/** 4 bytes, gives the size in bytes of the section data */
	private long sh_size;
	/**
	 * 4 bytes, section header table index link -- interpretation depends on
	 * section type
	 */
	private long sh_link;
	/** 4 bytes, extra info -- interpretation depends on section type */
	private long sh_info;
	/**
	 * 4 bytes, sh_addr must be congruent to 0 mod sh_addralign -- only 0 and
	 * positive powers of 2 are allowed
	 */
	private long sh_addralign;
	/**
	 * 4 bytes, some sections have a table of fixed-size entries, such as a
	 * symbol table; this member gives the size in bytes of each entry if that
	 * is the case. otherwise, this member is 0.
	 */
	private long sh_entsize;

	/*
	 * Nachos fields
	 */

	private int numPages;
	private int firstVPN;
	private OpenFile file;
    private static Privilege privilege = null;

    public static void enablePrivilege(Privilege p) {
        ElfSection.privilege = p;
    }

	/**
	 * Describes the type of a section. See individual members for further
	 * information.
	 * 
	 * @author Christopher Childs
	 * 
	 */
	public enum ElfSectionType {
		/**
		 * Inactive section
		 */
		SHT_NULL(0),
		/**
		 * Holds information defined by the program, whose format and meaning
		 * are determined solely by the program.
		 */
		SHT_PROGBITS(1),
		/**
		 * Holds a symbol table
		 */
		SHT_SYMTAB(2),
		/**
		 * Holds a symbol table
		 */
		SHT_DYNSYM(11),
		/**
		 * Holds a string table
		 */
		SHT_STRTAB(3),
		/**
		 * Holds relocation entries with explicit addends, such as type
		 * Elf32_Rela for the 32-bit class of object files. An object file may
		 * have multiple relocation sections. Almost assuredly not used by
		 * Nachos.
		 */
		SHT_RELA(4),
		/**
		 * Holds a symbol hash table
		 */
		SHT_HASH(5),
		/**
		 * Holds information for dynamic linking (not used by Nachos)
		 */
		SHT_DYNAMIC(6),
		/**
		 * Holds information that marks the file in some way
		 */
		SHT_NOTE(7),
		/**
		 * Section occupies no space in the file, but otherwise resembles
		 * SHT_PROGBITS. sh_offset member field isn't valid.
		 */
		SHT_NOBITS(8),
		/**
		 * Holds relocation entries without explicit addends, such as type
		 * Elf32_Rel for the 32-bit class of object files. An object file may
		 * have multiple relocation sections. Almost assuredly not used by
		 * Nachos.
		 */
		SHT_REL(9),
		/**
		 * Section is reserved, with unspecified semantics.
		 */
		SHT_SHLIB(10);

		private long value;

		private ElfSectionType(long value) {
			this.value = value;
		}

		public long getValue() {
			return this.value;
		}

		private static Map<Long, ElfSectionType> longToElfSectionType = new HashMap<Long, ElfSectionType>();

		static {
			for (ElfSectionType c : ElfSectionType.values()) {
				ElfSectionType.longToElfSectionType.put(c.getValue(), c);
			}
		}

		public static ElfSectionType valueToSectionType(long value) {
			return ElfSectionType.longToElfSectionType.get(value);
		}
	}

	/**
	 * Section attribute flags.
	 * 
	 * @author Christopher Childs
	 * 
	 */
	public enum ElfSectionFlag {
		/**
		 * Section should be writable during process execution
		 */
		SHF_WRITE(1),
		/**
		 * Section is memory resident
		 */
		SHF_ALLOC(2),
		/**
		 * Section contains executable machine instructions
		 */
		SHF_EXECINSTR(4);
		/**
		 * All bits included in this mask are reserved for processor-specific
		 * semantics
		 */
		// SHF_MASKPROC(0xF0000000);

		private long value;

		private ElfSectionFlag(long value) {
			this.value = value;
		}

		public long getValue() {
			return this.value;
		}

		private static Map<Long, ElfSectionFlag> longToElfSectionFlag = new HashMap<Long, ElfSectionFlag>();

		static {
			for (ElfSectionFlag f : ElfSectionFlag.values()) {
				ElfSectionFlag.longToElfSectionFlag.put(f.getValue(), f);
			}
		}

		public static ElfSectionFlag valueToSectionType(long value) {
			return ElfSectionFlag.longToElfSectionFlag.get(value);
		}
	}

	private Elf object;

	public ElfSection(Elf object, OpenFile file, byte data[]) {
		this.object = object;
		this.file = file;
        privilege.doPrivileged(new Runnable() {
            public void run() { flags = EnumSet.noneOf(ElfSectionFlag.class); }
        });
		parseHeader(data);

		if (flags.contains(ElfSectionFlag.SHF_ALLOC)) {
			/*
			 * This section is memory resident; we can calculate stuff like
			 * numPages and firstVPN
			 */
			this.numPages = Lib.divRoundUp((int) this.sh_size,
					Processor.pageSize);
			this.firstVPN = (int) this.sh_addr / Processor.pageSize;
		} else {
			this.numPages = 0;
			this.firstVPN = 0;
		}
	}

	private void parseHeader(byte data[]) {
		this.sh_name = this.object.elf32_wordToLong(data, 0);
		this.sh_type = this.object.elf32_wordToLong(data, 4);
		this.type = ElfSectionType.valueToSectionType(this.sh_type);
		this.sh_flags = this.object.elf32_wordToLong(data, 8);
		interpretFlags();
		this.sh_addr = this.object.elf32_wordToLong(data, 12);
		this.sh_offset = this.object.elf32_wordToLong(data, 16);
		this.sh_size = this.object.elf32_wordToLong(data, 20);
		this.sh_link = this.object.elf32_wordToLong(data, 24);
		this.sh_info = this.object.elf32_wordToLong(data, 28);
		this.sh_addralign = this.object.elf32_wordToLong(data, 32);
		this.sh_entsize = this.object.elf32_wordToLong(data, 36);
	}

	/**
	 * Reads sh_flags and populates the EnumSet with them.
	 */
	private void interpretFlags() {
		for (ElfSectionFlag f : ElfSectionFlag.values()) {
			if ((this.sh_flags & f.getValue()) == f.getValue()) {
				this.flags.add(f);
			}
		}
	}

	public void printInfo() {
		System.out.printf("Section name: %s\n", this.sectionName);
		System.out
				.printf("Section contents offset: %08x\tVirtual address: %08x (addr. align: 2**%d)\n",
						this.sh_offset, this.sh_addr,
						(int) Math.sqrt((double) this.sh_addralign));
		System.out.printf("Size: %d (%x) bytes\tType: %s\n", this.sh_size,
				this.sh_size, this.type);
		if (!this.flags.isEmpty()) {
			System.out.print("Flags: ");
			for (ElfSectionFlag f : this.flags) {
				System.out.printf("%s ", f);
			}
			System.out.println();
		}
		System.out.println();
	}

	public ElfSectionType getType() {
		return this.type;
	}

	/**
	 * 
	 * @return The offset from the beginning of the file to the section contents
	 */
	public long getOffset() {
		return this.sh_offset;
	}

	/**
	 * 
	 * @return The size in bytes of the section data (less than or equal to the page size)
	 */
	public long getSize() {
		return this.sh_size;
	}

	public void getNameFromTable() {
		this.sectionName = this.object.shStringTableLookup(this.sh_name);
	}

	/**
	 * Load a page from this segment into physical memory.
	 * 
	 * Essentially the same as the COFF version.
	 * 
	 * @param spn
	 *            the page number within this segment.
	 * @param ppn
	 *            the physical page to load into.
	 */
	public void loadPage(int spn, int ppn) {
		Lib.assertTrue(file != null);

		Lib.assertTrue(spn >= 0 && spn < numPages);
		Lib.assertTrue(ppn >= 0 && ppn < Machine.processor().getNumPhysPages());

		int pageSize = Processor.pageSize;
		byte[] memory = Machine.processor().getMemory();
		int paddr = ppn * pageSize;
		int faddr = (int) this.sh_offset + spn * pageSize;
		int initlen;

		if (this.type == ElfSectionType.SHT_NOBITS)
			initlen = 0;
		else if (spn == numPages - 1)
			/**
			 * initlen = size % pageSize; Bug identified by Steven Schlansker
			 * 3/20/08 Bug fix by Michael Rauser
			 */
			initlen = (int)((this.sh_size == pageSize) ? pageSize : (this.sh_size % pageSize));
		else
			initlen = pageSize;

		if (initlen > 0)
			Lib.strictReadFile(file, faddr, memory, paddr, initlen);

		Arrays.fill(memory, paddr + initlen, paddr + pageSize, (byte) 0);
	}

	public int getFirstVPN() {
		return this.firstVPN;
	}

	public int getNumPages() {
		return this.numPages;
	}

	public boolean loadable() {
		return flags.contains(ElfSectionFlag.SHF_ALLOC);
	}
	
	public String getName() {
		return this.sectionName;
	}

	public boolean isReadOnly() {
		if (!flags.contains(ElfSectionFlag.SHF_WRITE)) {
			return true;
		}
		
		return false;
	}
}
