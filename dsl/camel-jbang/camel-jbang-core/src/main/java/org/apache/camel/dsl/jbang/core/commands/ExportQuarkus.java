/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.core.commands;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.camel.main.MavenGav;
import org.apache.camel.util.FileUtil;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.OrderedProperties;
import org.apache.camel.util.StringHelper;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine;

@CommandLine.Command(name = "quarkus", description = "Export as Quarkus project")
class ExportQuarkus extends BaseExport {

    @CommandLine.Option(names = { "--quarkus-version" }, description = "Quarkus version",
                        defaultValue = "2.9.2.Final")
    private String quarkusVersion;

    public ExportQuarkus(CamelJBangMain main) {
        super(main);
    }

    @Override
    public Integer call() throws Exception {
        String[] ids = gav.split(":");
        if (ids.length != 3) {
            System.out.println("--gav must be in syntax: groupId:artifactId:version");
            return 1;
        }

        File profile = new File(getProfile() + ".properties");

        // the settings file has information what to export
        File settings = new File(Run.WORK_DIR + "/" + Run.RUN_SETTINGS_FILE);
        if (fresh || !settings.exists()) {
            // allow to automatic build
            System.out.println("Generating fresh run data");
            int silent = runSilently();
            if (silent != 0) {
                return silent;
            }
        } else {
            System.out.println("Reusing existing run data");
        }

        System.out.println("Exporting as Quarkus project to: " + exportDir);

        // use a temporary work dir
        File buildDir = new File(BUILD_DIR);
        FileUtil.removeDir(buildDir);
        buildDir.mkdirs();

        // copy source files
        String packageName = ids[0] + "." + ids[1];
        File srcJavaDir = new File(BUILD_DIR, "src/main/java/" + packageName.replace('.', '/'));
        srcJavaDir.mkdirs();
        File srcResourcesDir = new File(BUILD_DIR, "src/main/resources");
        srcResourcesDir.mkdirs();
        File srcCamelResourcesDir = new File(BUILD_DIR, "src/main/resources/camel");
        srcCamelResourcesDir.mkdirs();
        copySourceFiles(settings, profile, srcJavaDir, srcResourcesDir, srcCamelResourcesDir, packageName);
        // copy from settings to profile
        copySettingsAndProfile(settings, profile, srcResourcesDir);
        // gather dependencies
        Set<String> deps = resolveDependencies(settings);
        // create pom
        createPom(new File(BUILD_DIR, "pom.xml"), deps);

        if (exportDir.equals(".")) {
            // we export to current dir so prepare for this by cleaning up existing files
            File target = new File(exportDir);
            for (File f : target.listFiles()) {
                if (!f.isHidden() && f.isDirectory()) {
                    FileUtil.removeDir(f);
                } else if (!f.isHidden() && f.isFile()) {
                    f.delete();
                }
            }
        }
        // copy to export dir and remove work dir
        FileUtils.copyDirectory(new File(BUILD_DIR), new File(exportDir));
        FileUtil.removeDir(new File(BUILD_DIR));

        return 0;
    }

    private void createPom(File pom, Set<String> deps) throws Exception {
        String[] ids = gav.split(":");

        InputStream is = ExportQuarkus.class.getClassLoader().getResourceAsStream("templates/quarkus-pom.tmpl");
        String context = IOHelper.loadText(is);
        IOHelper.close(is);

        context = context.replaceFirst("\\{\\{ \\.GroupId }}", ids[0]);
        context = context.replaceFirst("\\{\\{ \\.ArtifactId }}", ids[1]);
        context = context.replaceFirst("\\{\\{ \\.Version }}", ids[2]);
        context = context.replaceAll("\\{\\{ \\.QuarkusVersion }}", quarkusVersion);
        context = context.replaceFirst("\\{\\{ \\.JavaVersion }}", javaVersion);

        StringBuilder sb = new StringBuilder();
        for (String dep : deps) {
            MavenGav gav = MavenGav.parseGav(null, dep);
            String gid = gav.getGroupId();
            String aid = gav.getArtifactId();
            String v = gav.getVersion();
            // transform to camel-quarkus extension GAV
            if ("org.apache.camel".equals(gid)) {
                gid = "org.apache.camel.quarkus";
                aid = aid.replace("camel-", "camel-quarkus-");
                v = null;
            }
            sb.append("        <dependency>\n");
            sb.append("            <groupId>").append(gid).append("</groupId>\n");
            sb.append("            <artifactId>").append(aid).append("</artifactId>\n");
            if (v != null) {
                sb.append("            <version>").append(v).append("</version>\n");
            }
            sb.append("        </dependency>\n");
        }
        context = context.replaceFirst("\\{\\{ \\.CamelDependencies }}", sb.toString());

        IOHelper.writeText(context, new FileOutputStream(pom, false));
    }

    private Set<String> resolveDependencies(File settings) throws Exception {
        Set<String> answer = new TreeSet<>((o1, o2) -> {
            // favour org.apache.camel first
            boolean c1 = o1.contains("org.apache.camel:");
            boolean c2 = o2.contains("org.apache.camel:");
            if (c1 && !c2) {
                return -1;
            } else if (!c1 && c2) {
                return 1;
            }
            return o1.compareTo(o2);
        });
        List<String> lines = Files.readAllLines(settings.toPath());
        for (String line : lines) {
            if (line.startsWith("dependency=")) {
                String v = StringHelper.after(line, "dependency=");
                // skip endpointdsl as its already included, and  core-languages and java-joor as we let quarkus compile
                boolean skip = v == null || v.contains("org.apache.camel:camel-core-languages")
                        || v.contains("org.apache.camel:camel-java-joor-dsl")
                        || v.contains("camel-endpointdsl");
                if (!skip) {
                    answer.add(v);
                }
                if (v != null && v.contains("org.apache.camel:camel-kamelet")) {
                    // include kamelet catalog if we use kamelets
                    answer.add("org.apache.camel.kamelets:camel-kamelets:" + kameletsVersion);
                }
            }
        }

        // remove out of the box dependencies
        answer.removeIf(s -> s.contains("camel-core"));
        answer.removeIf(s -> s.contains("camel-platform-http"));
        answer.removeIf(s -> s.contains("camel-microprofile-health"));

        // remove duplicate versions (keep first)
        Map<String, String> versions = new HashMap<>();
        Set<String> toBeRemoved = new HashSet<>();
        for (String line : answer) {
            MavenGav gav = MavenGav.parseGav(null, line);
            String ga = gav.getGroupId() + ":" + gav.getArtifactId();
            if (!versions.containsKey(ga)) {
                versions.put(ga, gav.getVersion());
            } else {
                toBeRemoved.add(line);
            }
        }
        answer.removeAll(toBeRemoved);

        return answer;
    }

    private void copySourceFiles(
            File settings, File profile, File srcJavaDir, File srcResourcesDir, File srcCamelResourcesDir, String packageName)
            throws Exception {
        // read the settings file and find the files to copy
        OrderedProperties prop = new OrderedProperties();
        prop.load(new FileInputStream(settings));

        for (String k : SETTINGS_PROP_SOURCE_KEYS) {
            String files = prop.getProperty(k);
            if (files != null) {
                for (String f : files.split(",")) {
                    String scheme = getScheme(f);
                    if (scheme != null) {
                        f = f.substring(scheme.length() + 1);
                    }
                    boolean skip = profile.getName().equals(f); // skip copying profile
                    if (skip) {
                        continue;
                    }
                    String ext = FileUtil.onlyExt(f, true);
                    boolean java = "java".equals(ext);
                    boolean camel = "camel.main.routesIncludePattern".equals(k) || "camel.component.kamelet.location".equals(k);
                    File target = java ? srcJavaDir : camel ? srcCamelResourcesDir : srcResourcesDir;
                    File source = new File(f);
                    File out = new File(target, source.getName());
                    safeCopy(source, out, true);
                    if (java) {
                        // need to append package name in java source file
                        List<String> lines = Files.readAllLines(out.toPath());
                        lines.add(0, "");
                        lines.add(0, "package " + packageName + ";");
                        FileOutputStream fos = new FileOutputStream(out);
                        for (String line : lines) {
                            fos.write(line.getBytes(StandardCharsets.UTF_8));
                            fos.write("\n".getBytes(StandardCharsets.UTF_8));
                        }
                        IOHelper.close(fos);
                    }
                }
            }
        }
    }

    private void copySettingsAndProfile(File settings, File profile, File targetDir) throws Exception {
        OrderedProperties prop = new OrderedProperties();
        prop.load(new FileInputStream(settings));
        OrderedProperties prop2 = new OrderedProperties();
        if (profile.exists()) {
            prop2.load(new FileInputStream(profile));
        }

        for (Map.Entry<Object, Object> entry : prop.entrySet()) {
            String key = entry.getKey().toString();
            // modeline not supported in camel-quarkus
            boolean skip = "camel.main.modeline".equals(key) || "camel.main.routesCompileDirectory".equals(key)
                    || "camel.main.routesReloadEnabled".equals(key);
            if (!skip && key.startsWith("camel.main")) {
                prop2.put(entry.getKey(), entry.getValue());
            }
        }

        FileOutputStream fos = new FileOutputStream(new File(targetDir, "application.properties"), false);
        for (Map.Entry<Object, Object> entry : prop2.entrySet()) {
            String k = entry.getKey().toString();
            String v = entry.getValue().toString();

            boolean skip = k.startsWith("camel.jbang.");
            if (skip) {
                continue;
            }

            // files are now loaded in classpath
            v = v.replaceAll("file:", "classpath:");
            if ("camel.main.routesIncludePattern".equals(k)) {
                // camel.main.routesIncludePattern should remove all .java as we use quarkus to load them
                // camel.main.routesIncludePattern should remove all file: classpath: as we copy them to src/main/resources/camel where camel auto-load from
                v = Arrays.stream(v.split(","))
                        .filter(n -> !n.endsWith(".java") && !n.startsWith("file:") && !n.startsWith("classpath:"))
                        .collect(Collectors.joining(","));
            }
            if (!v.isBlank()) {
                String line = k + "=" + v;
                fos.write(line.getBytes(StandardCharsets.UTF_8));
                fos.write("\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        IOHelper.close(fos);
    }

}