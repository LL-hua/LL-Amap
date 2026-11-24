package com.javacad.container;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.*;

public class R2004Container {
    public static class PageSlot {
        public int id;
        public long address;
        public PageSlot(int id, long address) { this.id = id; this.address = address; }
    }

    public static class SectionEntry {
        public long size;
        public int pageCount;
        public int maxDecompressed;
        public int compressed; // 1=无压缩 2=LZ77
        public int encrypted;
        public String name;
        public List<Integer> pageIds = new ArrayList<>();
    }

    private static class R2004Header {
        long sectionPageMapAddress;
        int sectionMapID;
    }

    private static final int HeaderOffset = 0x80;
    private static final int HeaderSize = 0x6C;
    private static final int SystemSectionHdrSize = 0x14;
    private static final int DataSectionMagic = 0x4163043B;

    private static final int SectionPageMapMagic = 0x41630E3B;
    private static final int SectionMapMagic = 0x4163003B;

    private static byte[] lcgKeyStream() {
        byte[] seq = new byte[HeaderSize];
        int seed = 1;
        for (int i = 0; i < seq.length; i++) {
            seed = seed * 0x343FD + 0x269EC3;
            seq[i] = (byte) (seed >>> 16);
        }
        return seq;
    }

    private static R2004Header decryptHeader(byte[] data) throws Exception {
        if (data.length < HeaderOffset + HeaderSize) throw new Exception("文件过小，缺少容器头");
        byte[] keys = lcgKeyStream();
        byte[] plain = new byte[HeaderSize];
        for (int i = 0; i < HeaderSize; i++) {
            plain[i] = (byte) (data[HeaderOffset + i] ^ keys[i]);
        }
        ByteBuffer bb = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
        R2004Header hdr = new R2004Header();
        hdr.sectionPageMapAddress = bb.getLong(0x54);
        hdr.sectionMapID = bb.getInt(0x5C);
        return hdr;
    }

    private static byte[] inflateSystemSection(byte[] data, long address, int expectMagic) throws Exception {
        int offset = (int) address;
        if (offset < 0 || offset + SystemSectionHdrSize > data.length) throw new Exception("System Section 越界");
        ByteBuffer bb = ByteBuffer.wrap(data, offset, SystemSectionHdrSize).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        int plainSize = bb.getInt();
        int packedSize = bb.getInt();
        int codec = bb.getInt();

        if (magic != expectMagic) throw new Exception(String.format("System Section 签名不匹配: 0x%08X", magic));
        if (packedSize == 0) return new byte[0];

        int start = offset + SystemSectionHdrSize;
        byte[] packed = new byte[packedSize];
        System.arraycopy(data, start, packed, 0, packedSize);

        if (codec == 1) {
            byte[] out = new byte[plainSize];
            System.arraycopy(packed, 0, out, 0, Math.min(plainSize, packedSize));
            return out;
        } else if (codec == 2) {
            return Lz77.decompress(packed, plainSize);
        }
        throw new Exception("不支持的压缩类型: " + codec);
    }

    public static List<PageSlot> parsePageMap(byte[] data, long sectionPageMapAddress) throws Exception {
        byte[] blob = inflateSystemSection(data, sectionPageMapAddress + 0x100, SectionPageMapMagic);
        List<PageSlot> slots = new ArrayList<>();
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        long pageAddress = 0x100;
        while (bb.remaining() >= 8) {
            int id = bb.getInt();
            int pageSize = bb.getInt();
            slots.add(new PageSlot(id, pageAddress));
            pageAddress += pageSize;
            if (id < 0) {
                if (bb.remaining() < 16) break;
                bb.position(bb.position() + 16);
            }
        }
        return slots;
    }

    public static List<SectionEntry> parseSectionMap(byte[] data, int sectionMapID, List<PageSlot> pages) throws Exception {
        PageSlot mapPage = null;
        for (PageSlot p : pages) if (p.id == sectionMapID) { mapPage = p; break; }
        if (mapPage == null) throw new Exception("页表中找不到段表页 id=" + sectionMapID);

        byte[] blob = inflateSystemSection(data, mapPage.address, SectionMapMagic);
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        if (blob.length < 20) throw new Exception("段表头截断");

        int entryCount = bb.getInt();
        bb.position(20);

        List<SectionEntry> sections = new ArrayList<>();
        for (int i = 0; i < entryCount; i++) {
            if (bb.remaining() < 96) break;
            SectionEntry sec = new SectionEntry();
            sec.size = bb.getLong();
            sec.pageCount = bb.getInt();
            sec.maxDecompressed = bb.getInt();
            bb.getInt();
            sec.compressed = bb.getInt();
            bb.getInt();
            sec.encrypted = bb.getInt();
            byte[] nameBytes = new byte[64];
            bb.get(nameBytes);
            int nz = 0; while (nz < 64 && nameBytes[nz] != 0) nz++;
            sec.name = new String(nameBytes, 0, nz, StandardCharsets.US_ASCII);

            for (int p = 0; p < sec.pageCount; p++) {
                if (bb.remaining() < 16) break;
                int pageId = bb.getInt();
                bb.getInt();
                bb.getLong();
                sec.pageIds.add(pageId);
            }
            sections.add(sec);
        }
        return sections;
    }

    private static byte[] unmaskPageHeader(byte[] b, long pageAddress) {
        byte[] out = new byte[32];
        System.arraycopy(b, 0, out, 0, 32);
        int mask = (int) (0x4164536B ^ (int) pageAddress);
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 32; i += 4) {
            bb.putInt(i, bb.getInt(i) ^ mask);
        }
        return out;
    }

    private static byte[] loadSectionPage(byte[] data, PageSlot slot, SectionEntry sec) throws Exception {
        int pageOffset = (int) slot.address;
        if (pageOffset + 32 > data.length) throw new Exception("数据页头越界");
        byte[] header = unmaskPageHeader(java.util.Arrays.copyOfRange(data, pageOffset, pageOffset + 32), slot.address);
        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        
        int magic = hb.getInt();
        if (magic != DataSectionMagic) throw new Exception(String.format("数据页签名不匹配 0x%08X", magic));
        int packedSize = hb.getInt(8);
        
        int dataStart = pageOffset + 32;
        int dataEnd = dataStart + packedSize;
        if (dataEnd > data.length) throw new Exception("数据页内容越界");

        byte[] packed = java.util.Arrays.copyOfRange(data, dataStart, dataEnd);
        if (sec.compressed == 2) {
            return Lz77.decompress(packed, sec.maxDecompressed);
        }
        return packed;
    }

    public static byte[] assembleSection(byte[] data, SectionEntry sec, List<PageSlot> pages) throws Exception {
        Map<Integer, PageSlot> lookup = new HashMap<>();
        for (PageSlot p : pages) lookup.put(p.id, p);

        int pageSize = sec.maxDecompressed;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(pageSize * sec.pageIds.size());

        for (int pageId : sec.pageIds) {
            PageSlot slot = lookup.get(pageId);
            if (slot == null) throw new Exception("页表中找不到页 id=" + pageId);
            byte[] pageData = loadSectionPage(data, slot, sec);
            out.write(pageData);
        }

        byte[] full = out.toByteArray();
        if (full.length > sec.size) {
            byte[] exact = new byte[(int) sec.size];
            System.arraycopy(full, 0, exact, 0, (int) sec.size);
            return exact;
        }
        return full;
    }

    public static byte[] getSectionData(byte[] data, String sectionName) throws Exception {
        R2004Header hdr = decryptHeader(data);
        List<PageSlot> pages = parsePageMap(data, hdr.sectionPageMapAddress);
        List<SectionEntry> sections = parseSectionMap(data, hdr.sectionMapID, pages);
        
        for (SectionEntry sec : sections) {
            if (sec.name.equals(sectionName)) {
                return assembleSection(data, sec, pages);
            }
        }
        return null;
    }
    public static Map<String, byte[]> getSections(byte[] data, String... names) throws Exception {
        R2004Header hdr = decryptHeader(data);
        List<PageSlot> pages = parsePageMap(data, hdr.sectionPageMapAddress);
        List<SectionEntry> sections = parseSectionMap(data, hdr.sectionMapID, pages);
        Set<String> target = new HashSet<>(Arrays.asList(names));
        Map<String, byte[]> res = new HashMap<>();
        for (SectionEntry sec : sections) {
            if (target.contains(sec.name)) res.put(sec.name, assembleSection(data, sec, pages));
        }
        return res;
    }
}