package com.winlator.core;

import com.winlator.container.ContainerFiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Import inventory and physical system DLL staging for the dedicated Battle.net prefix. */
public final class BattleNetDependencies {
    private BattleNetDependencies() {}
    private static final class Module {
        final File file;
        final PeDependencies.Image image;
        Module(File file) throws IOException { this.file = file; image = PeDependencies.read(file); }
        boolean x86() { return image.machine == 0x14c; }
    }

    public static String prepare(File upper, File lower, File wine, List<File> applicationRoots) throws IOException {
        StringBuilder report = new StringBuilder("Battle.net PE dependencies (static imports + delay imports)\n");
        report.append("API-set contracts are resolved by Wine; runtime LoadLibrary calls and API behavior require a live check.\n");
        List<Module> applications = new ArrayList<>();
        int[] scanned = {0};
        for (File root : applicationRoots) scan(root, root, 0, scanned, applications, report);
        Map<String, List<Module>> bundled = new HashMap<>();
        for (Module m : applications) bundled.computeIfAbsent(key(m.x86(), m.file.getName()), ignored -> new ArrayList<>()).add(m);
        ArrayDeque<Module> queue = new ArrayDeque<>(applications);
        Set<String> visited = new HashSet<>(), contracts = new java.util.TreeSet<>(), missing = new java.util.TreeSet<>();
        Set<String> systemVisited = new HashSet<>();
        int staged = 0, system = 0;
        while (!queue.isEmpty()) {
            Module importer = queue.removeFirst();
            if (!visited.add(importer.file.getCanonicalPath())) continue;
            List<String> imports = new ArrayList<>(importer.image.imports);
            imports.addAll(importer.image.delayImports);
            for (String name : imports) {
                String normalized = name.toLowerCase(Locale.ROOT);
                boolean delayed = !importer.image.imports.contains(name);
                String label = (delayed ? "DELAY" : "IMPORT") + " " + importer.file.getName() + " -> " + name;
                if (normalized.startsWith("api-ms-") || normalized.startsWith("ext-ms-")) { contracts.add(normalized); continue; }
                String key = key(importer.x86(), normalized);
                List<Module> candidates = bundled.get(key);
                if (candidates != null) {
                    // A DLL beside the importer wins; other bundled modules are only evidence,
                    // not proof that Windows' per-process DLL search path includes that directory.
                    Module candidate = candidates.stream().filter(m -> m.file.getParentFile().equals(importer.file.getParentFile()))
                            .findFirst().orElse(candidates.get(0));
                    report.append(label).append(candidate.file.getParentFile().equals(importer.file.getParentFile()) ? " BUNDLED " : " BUNDLED_OTHER_DIRECTORY ")
                            .append(candidate.file.getPath()).append('\n');
                    continue;
                }
                if (systemVisited.contains(key)) continue;
                String relative = "drive_c/windows/" + (importer.x86() ? "syswow64/" : "system32/") + normalized;
                File target = new File(upper, relative);
                File effective = ContainerFiles.resolve(upper, lower, relative);
                Module module = valid(effective, importer.x86());
                if (module == null) {
                    // Never use Windows host DLLs. Recover only from this selected Wine package.
                    module = lower == null ? null : valid(new File(lower, relative), importer.x86());
                    if (module == null) module = valid(new File(wine, "lib/wine/" + (importer.x86() ? "i386-windows/" : "aarch64-windows/") + normalized), importer.x86());
                }
                if (module == null) {
                    String issue = label + " " + (effective == null ? "MISSING" : "INVALID_PE_OR_ARCH") + " [" + key + "]";
                    missing.add(issue);
                    continue;
                }
                systemVisited.add(key);
                boolean materialized = !target.isFile() || valid(target, importer.x86()) == null;
                if (materialized) {
                    copy(module.file, target);
                    module = new Module(target);
                    staged++;
                }
                system++;
                report.append(label).append(materialized ? " STAGED " : " PRESENT ")
                        .append(target.getPath()).append(" bytes=").append(target.length())
                        .append(" machine=0x").append(Integer.toHexString(module.image.machine)).append('\n');
                queue.add(module);
            }
        }
        prepareCommonControls(upper, lower, report);
        for (String issue : missing) report.append(issue).append('\n');
        report.append("API_SET_CONTRACTS ").append(String.join(",", contracts)).append('\n');
        report.append("SUMMARY applicationPE=").append(applications.size()).append(" systemPE=").append(system)
                .append(" staged=").append(staged).append(" unresolvedEdges=").append(missing.size()).append('\n');
        return report.toString();
    }

    private static String key(boolean x86, String name) { return (x86 ? "x86/" : "x64/") + name.toLowerCase(Locale.ROOT); }

    // A common-controls manifest redirects imports away from syswow64/system32 into WinSxS.
    // Cover that actual path too, including the exact uppercase spelling used by Chromium.
    static void prepareCommonControls(File upper, File lower, StringBuilder report) throws IOException {
        for (String system : new String[]{"syswow64", "system32"}) {
            String relative = "drive_c/windows/" + system + "/comctl32.dll";
            File source = ContainerFiles.resolve(upper, lower, relative);
            if (valid(source, system.equals("syswow64")) != null) {
                File target = new File(upper, relative);
                if (valid(target, system.equals("syswow64")) == null) copy(source, target);
                exactCommonControlsName(target, report);
            }
        }
        if (lower == null) return;
        File assemblies = new File(lower, "drive_c/windows/winsxs");
        File[] directories = assemblies.listFiles();
        if (directories == null) { report.append("SXS common-controls assemblies unavailable\n"); return; }
        for (File directory : directories) {
            String name = directory.getName();
            if (!(name.startsWith("x86_microsoft.windows.common-controls_") || name.startsWith("arm64_microsoft.windows.common-controls_")) || !directory.isDirectory()) continue;
            boolean x86 = name.startsWith("x86_");
            String relative = "drive_c/windows/winsxs/" + name + "/comctl32.dll";
            File target = new File(upper, relative);
            Module existing = valid(target, x86);
            if (existing == null) {
                File source = new File(directory, "comctl32.dll");
                if (valid(source, x86) == null) { report.append("SXS INVALID common-controls ").append(name).append('\n'); continue; }
                copy(source, target);
            }
            exactCommonControlsName(target, report);
            String manifest = "drive_c/windows/winsxs/manifests/" + name + ".manifest";
            File sourceManifest = new File(lower, manifest), targetManifest = new File(upper, manifest);
            if (!targetManifest.isFile() && sourceManifest.isFile()) copy(sourceManifest, targetManifest);
            report.append("SXS common-controls ").append(name).append(" bytes=").append(target.length())
                    .append(" manifest=").append(targetManifest.isFile() ? "present" : "MISSING").append('\n');
        }
    }

    private static void exactCommonControlsName(File canonical, StringBuilder report) throws IOException {
        File uppercase = new File(canonical.getParentFile(), "COMCTL32.dll");
        // An atomic repair replaces the canonical inode. Its old hard link (or a fallback
        // copy) then remains stale, so existence alone is not an integrity check.
        boolean sameFile = uppercase.exists() && Files.isSameFile(uppercase.toPath(), canonical.toPath());
        boolean sameBytes = !sameFile && uppercase.isFile() && uppercase.length() == canonical.length()
                && Arrays.equals(hash(uppercase), hash(canonical));
        File receipt = new File(canonical.getParentFile(), ".wow-forever-comctl-alias.sha256");
        if (!sameFile && !sameBytes) {
            // Only replace a valid different file if our receipt identifies it as our alias.
            // A manually installed native override must not be mistaken for an old copy.
            boolean managed = uppercase.isFile() && receipt.isFile() && receipt.length() == 32
                    && Arrays.equals(Files.readAllBytes(receipt.toPath()), hash(uppercase));
            if (!managed && valid(uppercase, PeDependencies.read(canonical).machine == 0x14c) != null) {
                report.append("COMCTL exact-name CONFLICT preserved existing native ").append(uppercase.getPath()).append('\n');
                return;
            }
            Files.deleteIfExists(uppercase.toPath());
            try { Files.createLink(uppercase.toPath(), canonical.toPath()); }
            catch (IOException | UnsupportedOperationException e) { copy(canonical, uppercase); }
        }
        Files.write(receipt.toPath(), hash(canonical));
        report.append("COMCTL exact-name ").append(uppercase.getPath()).append(" bytes=").append(uppercase.length()).append('\n');
    }
    private static Module valid(File file, boolean x86) {
        if (file == null || !file.isFile()) return null;
        try {
            Module m = new Module(file);
            int machine = m.image.machine;
            return (x86 ? machine == 0x14c : machine == 0x8664 || machine == 0xaa64 || machine == 0xa641 || machine == 0xa64e) ? m : null;
        } catch (IOException e) { return null; }
    }

    private static void scan(File root, File directory, int depth, int[] scanned, List<Module> modules, StringBuilder report) throws IOException {
        if (directory == null || !directory.isDirectory()) return;
        if (depth > 8) throw new IOException("Battle.net module directory depth exceeded");
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Couldn't enumerate Battle.net modules");
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) continue;
            if (Files.isSymbolicLink(file.toPath())) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (file.isDirectory()) {
                if (Arrays.asList("data", "logs", "log", "cache", "caches", ".battle.net", "crashdumps").contains(name)) continue;
                scan(root, file, depth + 1, scanned, modules, report);
                continue;
            }
            if (!(name.endsWith(".dll") || name.endsWith(".exe") || name.endsWith(".drv") || name.endsWith(".cpl"))) continue;
            if (++scanned[0] > 2048) throw new IOException("Too many Battle.net PE modules");
            try {
                Module m = new Module(file);
                if (m.image.machine != 0x14c && m.image.machine != 0x8664) {
                    report.append("UNSUPPORTED_APP_ARCH ").append(file.getPath()).append('\n');
                    continue;
                }
                modules.add(m);
            } catch (IOException e) { report.append("INVALID_APP_PE ").append(file.getPath()).append(" ").append(e.getMessage()).append('\n'); }
        }
    }

    private static void copy(File source, File target) throws IOException {
        Files.createDirectories(target.getParentFile().toPath());
        File temporary = File.createTempFile("bnet-dll-", ".part", target.getParentFile());
        try {
            Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (source.length() != temporary.length() || !Arrays.equals(hash(source), hash(temporary))) throw new IOException("Incomplete Wine DLL copy: " + source.getName());
            // Preserve valid native overrides, replacing only absent or structurally invalid files.
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
    private static byte[] hash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
                byte[] bytes = new byte[65536];
                int count;
                while ((count = in.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
}
