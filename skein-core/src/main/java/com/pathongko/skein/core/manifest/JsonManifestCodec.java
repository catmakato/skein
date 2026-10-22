package com.pathongko.skein.core.manifest;

import com.pathongko.skein.common.json.Json;
import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.DownloadSource;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.FileHash;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.domain.SyncPolicy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serializes the manifest records to and from JSON bytes using the tiny
 * {@link Json} codec. Hashes are stored self-contained (algorithm + value) so an
 * index file can be parsed without also reading the root. The same codec runs on
 * the server (generating) and the client (parsing), guaranteeing they agree — and
 * because {@link Json} writes keys in sorted order, the encoded bytes are stable,
 * which is what makes index/root hashes reproducible.
 */
public final class JsonManifestCodec {

    public byte[] encodeRoot(RootManifest root) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", root.schemaVersion());
        map.put("packId", root.packId());
        map.put("packVersion", root.packVersion().raw());
        map.put("generatedAt", root.generatedAt().toString());
        map.put("hashAlgorithm", root.hashAlgorithm().name());
        List<Object> indices = new ArrayList<>();
        for (IndexRef ref : root.indices()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("category", ref.category().name());
            r.put("file", ref.file());
            r.put("hash", encodeHash(ref.hash()));
            r.put("entryCount", ref.entryCount());
            indices.add(r);
        }
        map.put("indices", indices);
        map.put("signature", root.signature().orElse(null));
        return bytes(map);
    }

    @SuppressWarnings("unchecked")
    public RootManifest decodeRoot(byte[] data) {
        Map<String, Object> map = (Map<String, Object>) Json.read(str(data));
        List<Object> rawIndices = (List<Object>) map.get("indices");
        List<IndexRef> indices = new ArrayList<>();
        for (Object o : rawIndices) {
            Map<String, Object> r = (Map<String, Object>) o;
            indices.add(new IndexRef(
                    FileCategory.valueOf((String) r.get("category")),
                    (String) r.get("file"),
                    decodeHash((Map<String, Object>) r.get("hash")),
                    asInt(r.get("entryCount"))));
        }
        return new RootManifest(
                asInt(map.get("schemaVersion")),
                (String) map.get("packId"),
                PackVersion.of((String) map.get("packVersion")),
                Instant.parse((String) map.get("generatedAt")),
                HashAlgorithm.valueOf((String) map.get("hashAlgorithm")),
                indices,
                Optional.ofNullable((String) map.get("signature")));
    }

    public byte[] encodeIndex(CategoryIndex index) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("category", index.category().name());
        List<Object> entries = new ArrayList<>();
        for (FileEntry e : index.entries()) {
            entries.add(encodeEntry(e));
        }
        map.put("entries", entries);
        return bytes(map);
    }

    @SuppressWarnings("unchecked")
    public CategoryIndex decodeIndex(byte[] data) {
        Map<String, Object> map = (Map<String, Object>) Json.read(str(data));
        List<Object> rawEntries = (List<Object>) map.get("entries");
        List<FileEntry> entries = new ArrayList<>();
        for (Object o : rawEntries) {
            entries.add(decodeEntry((Map<String, Object>) o));
        }
        return new CategoryIndex(FileCategory.valueOf((String) map.get("category")), entries);
    }

    // --- entry ---------------------------------------------------------------

    private Map<String, Object> encodeEntry(FileEntry e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", e.path().value());
        map.put("size", e.sizeBytes());
        map.put("hash", encodeHash(e.hash()));
        map.put("category", e.category().name());
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("required", e.policy().required());
        policy.put("onExtra", e.policy().onExtra().name());
        policy.put("clientOnlyAllowed", e.policy().clientOnlyAllowed());
        map.put("policy", policy);
        List<Object> sources = new ArrayList<>();
        for (DownloadSource s : e.orderedSources()) {
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("uri", s.uri().toString());
            src.put("priority", s.priority());
            sources.add(src);
        }
        map.put("sources", sources);
        return map;
    }

    @SuppressWarnings("unchecked")
    private FileEntry decodeEntry(Map<String, Object> map) {
        Map<String, Object> policy = (Map<String, Object>) map.get("policy");
        SyncPolicy syncPolicy = new SyncPolicy(
                (Boolean) policy.get("required"),
                DeletionPolicy.valueOf((String) policy.get("onExtra")),
                (Boolean) policy.get("clientOnlyAllowed"));
        List<DownloadSource> sources = new ArrayList<>();
        for (Object o : (List<Object>) map.get("sources")) {
            Map<String, Object> src = (Map<String, Object>) o;
            sources.add(new DownloadSource(URI.create((String) src.get("uri")), asInt(src.get("priority"))));
        }
        return new FileEntry(
                new RelativePath((String) map.get("path")),
                asLong(map.get("size")),
                decodeHash((Map<String, Object>) map.get("hash")),
                FileCategory.valueOf((String) map.get("category")),
                syncPolicy,
                new java.util.LinkedHashSet<>(sources));
    }

    // --- helpers -------------------------------------------------------------

    private static Map<String, Object> encodeHash(FileHash hash) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("algorithm", hash.algorithm().name());
        map.put("value", hash.value());
        return map;
    }

    private static FileHash decodeHash(Map<String, Object> map) {
        return new FileHash(HashAlgorithm.valueOf((String) map.get("algorithm")), (String) map.get("value"));
    }

    private static byte[] bytes(Object value) {
        return Json.write(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int asInt(Object number) {
        return ((Number) number).intValue();
    }

    private static long asLong(Object number) {
        return ((Number) number).longValue();
    }
}
