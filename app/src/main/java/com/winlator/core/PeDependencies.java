package com.winlator.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Bounded, read-only PE import reader. Does not load or execute the inspected module. */
public final class PeDependencies {
    private PeDependencies() {}

    public static final class Image {
        public final int machine;
        public final List<String> imports = new ArrayList<>();
        public final List<String> delayImports = new ArrayList<>();
        private Image(int machine) { this.machine = machine; }
    }

    public static Image read(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 64 || word(in, 0) != 0x5a4d) throw new IOException("Invalid DOS header");
            long pe = dword(in, 60);
            if (pe < 64 || pe > in.length() - 24 || dword(in, pe) != 0x4550) throw new IOException("Invalid PE header");
            Image result = new Image(word(in, pe + 4));
            int count = word(in, pe + 6), optionalSize = word(in, pe + 20);
            long optional = pe + 24;
            int magic = word(in, optional);
            if ((magic != 0x10b && magic != 0x20b) || count < 1 || count > 96 || optionalSize < (magic == 0x10b ? 96 : 112))
                throw new IOException("Invalid optional header");
            long directories = optional + (magic == 0x10b ? 96 : 112);
            long imageBase = magic == 0x10b ? dword(in, optional + 28) : qword(in, optional + 24);
            long[][] sections = new long[count][3];
            long table = optional + optionalSize;
            if (table + count * 40L > in.length()) throw new IOException("Truncated section table");
            for (int i = 0; i < count; i++) {
                long entry = table + i * 40L;
                long size = dword(in, entry + 16), offset = dword(in, entry + 20);
                if (size > 0 && (offset > in.length() || size > in.length() - offset)) throw new IOException("Truncated PE section");
                sections[i] = new long[]{dword(in, entry + 12), size, offset};
            }
            long directoryCount = dword(in, directories - 4);
            if (directoryCount > 1 && directories + 16 <= optional + optionalSize)
                imports(in, sections, dword(in, directories + 8), false, imageBase, result.imports);
            if (directoryCount > 13 && directories + 112 <= optional + optionalSize)
                imports(in, sections, dword(in, directories + 104), true, imageBase, result.delayImports);
            return result;
        }
    }

    private static void imports(RandomAccessFile in, long[][] sections, long rva, boolean delay, long base, List<String> names) throws IOException {
        if (rva == 0) return;
        int stride = delay ? 32 : 20;
        for (int i = 0; i < 1024; i++) {
            long offset = offset(sections, rva + (long)i * stride, stride);
            long name = dword(in, offset + (delay ? 4 : 12));
            if (name == 0) return;
            if (delay && (dword(in, offset) & 1) == 0) name -= base;
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < 260; j++) {
                in.seek(offset(sections, name + j, 1));
                int ch = in.readUnsignedByte();
                if (ch == 0) {
                    if (text.length() == 0) throw new IOException("Empty import name");
                    names.add(text.toString());
                    break;
                }
                if (ch < 32 || ch > 126 || ch == '/' || ch == '\\' || ch == ':') throw new IOException("Invalid import name");
                text.append((char)ch);
                if (j == 259) throw new IOException("Unterminated import name");
            }
        }
        throw new IOException("Unterminated imports");
    }

    private static long offset(long[][] sections, long rva, int bytes) throws IOException {
        for (long[] section : sections) {
            long relative = rva - section[0];
            if (relative >= 0 && relative <= section[1] - bytes) return section[2] + relative;
        }
        throw new IOException("Import RVA outside file-backed sections");
    }
    private static int word(RandomAccessFile in, long offset) throws IOException {
        in.seek(offset);
        return in.readUnsignedByte() | in.readUnsignedByte() << 8;
    }
    private static long dword(RandomAccessFile in, long offset) throws IOException {
        return (long)word(in, offset) | (long)word(in, offset + 2) << 16;
    }
    private static long qword(RandomAccessFile in, long offset) throws IOException {
        return dword(in, offset) | dword(in, offset + 4) << 32;
    }
}
