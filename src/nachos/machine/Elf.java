package nachos.machine;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes an ELF32 binary. Ultimately intended for usage with Nachos 5.0j to
 * supplant the obsolete COFF file format; tools to build binaries that Nachos
 * can handle have been obsolete for several years as of the creation of this
 * ELF binary support class.
 * 
 * This code is only "smart" enough to handle statically linked binaries.
 * 
 * This code should also be commented enough to reflect the Tool Interface
 * Standard ELF Specification 1.2.
 * 
 * In order to make this as easy to work with as possible, the linker should
 * align pages on Nachos pageSize (1024 / 0x400 byte) boundaries. It should also
 * place all SHF_ALLOC flagged pages at the front, so it'd be easy to identify
 * the number of sections to load by just looking at the PT_LOAD program header
 * entry's memory image size. Of course, some of those could be uninitialized
 * .bss segments and may not necessarily need to copy anything from the file
 * image to memory.
 * 
 * This will sort of be modeled after Nachos' Coff and CoffSection.
 * 
 * There is lots of superfluous stuff kept and read from the ELF header; it's
 * just here to be complete.
 * 
 * @author Christopher Childs
 * 
 */
public class Elf {
	/*
	 * Java doesn't have unsigned types, so the values stored here are larger
	 * than what are specified in an ELF header.
	 */

	private enum ElfClass {
		invalid(0), class32(1), class64(2);

		private int value;

		private ElfClass(int value) {
			this.value = value;
		}

		public int getValue() {
			return this.value;
		}

		private static Map<Integer, ElfClass> intToElfClass = new HashMap<Integer, ElfClass>();

		static {
			for (ElfClass c : ElfClass.values()) {
				ElfClass.intToElfClass.put(c.getValue(), c);
			}
		}

		public static ElfClass valueToClass(int value) {
			return ElfClass.intToElfClass.get(value);
		}
	}

	private enum ElfType {
		ET_NONE(0), ET_REL(1), ET_EXEC(2), ET_DYN(3), ET_CORE(4);

		private int value;

		private ElfType(int value) {
			this.value = value;
		}

		public int getValue() {
			return this.value;
		}

		private static Map<Integer, ElfType> intToElfType = new HashMap<Integer, ElfType>();

		static {
			for (ElfType c : ElfType.values()) {
				ElfType.intToElfType.put(c.getValue(), c);
			}
		}

		public static ElfType valueToType(int value) {
			return ElfType.intToElfType.get(value);
		}
	}

	private static final int headerSize = 52;

	/** 16 bytes, ELF identification characteristics */
	private ByteOrder order;
	private ElfClass elfClass;
	private ElfType elfType;

	/** 2 bytes */
	private int e_type;

	/** 2 bytes */
	private int e_machine;
	/** 4 bytes */
	private long e_version;
	/** 4 bytes, Entry point virtual address */
	private long e_entry;
	/** 4 bytes, program header table's file offset in bytes */
	private long e_phoff;
	/** 4 bytes, section header table's file offset in bytes */
	private long e_shoff;
	/** 4 bytes, processor-specific flags associated with the file */
	private long e_flags;
	/** 2 bytes, ELF header size in bytes */
	private int e_ehsize;
	/** 2 bytes, size of an entry in the program header table */
	private int e_phentsize;
	/** 2 bytes, number of entries in the program header table */
	private int e_phnum;
	/** 2 bytes, size of an entry in the section header table */
	private int e_shentsize;
	/** 2 bytes, number of entries in the section header table */
	private int e_shnum;
	/**
	 * 2 bytes, section header table index of the entry associated w/ the string
	 * table
	 */
	private int e_shstrndx;
	private ElfSection strTable;

	private ElfSection sections[];

	private ElfProgram phEntries[];

	private boolean valid;

	private OpenFile theFile;

	public Elf(OpenFile file) throws EOFException {
		this.valid = false;
		this.theFile = file;
		byte data[] = new byte[Elf.headerSize];
		this.theFile.read(data, 0, data.length);
		readHeaderData(data);
	}

	private boolean readHeaderData(byte data[]) {
		/*
		 * Magic detection. All ELF objects start with 0x7F, E, L, F
		 */
		if (data[0] == 0x7F && data[1] == 'E' || data[1] == 'L'
				|| data[2] == 'F') {
			this.valid = true;
		} else {
			return false;
		}

		/*
		 * Class detection. 32-bit binaries using up to 4GB of VM space will use
		 * the 32-bit class. The reference used to implement this class doesn't
		 * specify how behavior changes with the 64-bit class.
		 */
		this.elfClass = ElfClass.valueToClass(data[4]);
		if (this.elfClass == ElfClass.invalid) {
			this.valid = false;
			return false;
		}

		/*
		 * Endian-ness detection.
		 */
		switch (data[5]) {
		case 1:
			this.order = ByteOrder.LITTLE_ENDIAN;
			break;
		case 2:
			// Nachos is little endian; this probably shouldn't fly
			this.order = ByteOrder.BIG_ENDIAN;
			break;
		default:
			this.valid = false;
			return false;
		}

		// bytes 16-17: e_type
		this.e_type = elf32_halfToInteger(data, 16);

		// bytes 18-19: e_machine
		this.e_machine = elf32_halfToInteger(data, 18);

		// bytes 20-23: e_version
		this.e_version = elf32_wordToLong(data, 20);

		// bytes 24-27: e_entry
		this.e_entry = elf32_wordToLong(data, 24);

		// bytes 28-31: e_phoff
		this.e_phoff = elf32_wordToLong(data, 28);

		// bytes 32-35: e_shoff
		this.e_shoff = elf32_wordToLong(data, 32);

		// bytes 36-39: e_flags
		this.e_flags = elf32_wordToLong(data, 36);

		// bytes 40-41: e_ehsize
		this.e_flags = elf32_halfToInteger(data, 40);

		// bytes 42-43: e_phentsize
		this.e_phentsize = elf32_halfToInteger(data, 42);

		// bytes 44-45: e_phnum
		this.e_phnum = elf32_halfToInteger(data, 44);

		// bytes 46-47: e_shentsize
		this.e_shentsize = elf32_halfToInteger(data, 46);

		// bytes 48-49: e_shnum
		this.e_shnum = elf32_halfToInteger(data, 48);

		// bytes 50-51: e_shstrndx
		this.e_shstrndx = elf32_halfToInteger(data, 50);

		// allocate space for sections
		// we're pretending the null section doesn't exist
		this.sections = new ElfSection[this.e_shnum - 1];
		int offset = (int) this.e_shoff + this.e_shentsize;
		byte sectionHeaderData[] = new byte[this.e_shentsize];
		for (int i = 0; i < this.sections.length; i++) {
			this.theFile.seek(offset);
			this.theFile.read(sectionHeaderData, 0, this.e_shentsize);
			this.sections[i] = new ElfSection(this, this.theFile, sectionHeaderData);
			if (this.sections[i].getType() == ElfSection.ElfSectionType.SHT_STRTAB) {
				this.strTable = this.sections[i];
			}
			offset += this.e_shentsize;
		}

		/*
		 * now that all of the section data is loaded, they can name themselves.
		 */
		for (ElfSection s : this.sections) {
			s.getNameFromTable();
		}

		// allocate for program header entries
		this.phEntries = new ElfProgram[this.e_phnum];
		offset = (int) this.e_phoff;
		byte programHeaderData[] = new byte[this.e_phentsize];
		for (int i = 0; i < this.phEntries.length; i++) {
			this.theFile.seek(offset);
			this.theFile.read(programHeaderData, 0, this.e_phentsize);
			this.phEntries[i] = new ElfProgram(this, programHeaderData);
			offset += this.e_phentsize;
		}

		return true;
	}

	public int elf32_halfToInteger(byte buffer[], int offset) {
		byte newbytes[] = new byte[4];
		Arrays.fill(newbytes, (byte) 0);
        System.arraycopy(buffer, offset, newbytes,
                (this.order == ByteOrder.LITTLE_ENDIAN) ? 0 : 2, 2);
		ByteBuffer buf = ByteBuffer.wrap(newbytes).order(this.order);

		return buf.getInt();
	}

	public long elf32_wordToLong(byte buffer[], int offset) {
		byte newbytes[] = new byte[8];
		Arrays.fill(newbytes, (byte) 0);
        System.arraycopy(buffer, offset, newbytes,
                (this.order == ByteOrder.LITTLE_ENDIAN) ? 0 : 4, 4);
		ByteBuffer buf = ByteBuffer.wrap(newbytes).order(this.order);

		return buf.getLong();
	}

	public String shStringTableLookup(long offset) {
		// need to find the string table section

		if (this.strTable == null)
			return "(no string table loaded)";

		this.theFile.seek((int) (strTable.getOffset() + offset));
		// HACK: I have no idea if 32 is a good number or not, whatever
		CharBuffer buf = CharBuffer.allocate(32);
		byte b[] = new byte[1];
		int pos = 0;
		while (true) {
			this.theFile.read(b, 0, 1);
			buf.put((char)b[0]);
			++pos;
			if (b[0] == 0) {
				break;
			}
		}
		
		return new String(buf.array());
	}

	public boolean isValid() {
		return this.valid;
	}

	public ElfProgram getLoadProgramEntry() {
		for (ElfProgram p : this.phEntries) {
			if (p.getType() == ElfProgram.ElfProgramType.PT_LOAD) {
				return p;
			}
		}

		return null;
	}
	
	public ElfSection getSection(int section) {
		return this.sections[section];
	}
	
	public int getNumSections() {
		return this.sections.length;
	}

    /**
     * Frees allocated resources/open assets.
     *
     */
	public void close() {
        this.theFile.close();
        this.sections = null;
        this.phEntries = null;
	}

	public int getEntryPoint() {
		return (int)this.e_entry;
	}
}
