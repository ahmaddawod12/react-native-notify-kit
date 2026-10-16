#!/usr/bin/env python3
"""Exercise the shipped build.gradle with real AGP, Kotlin and React Native plugins.

Project files and React Native plugin builds stay in --work-dir; Gradle may populate
its normal user cache. The default matrix checks the Android DSL only. Boundary
cases above installed SDKs do not certify toolchain support. --metadata37 also
checks AAR metadata and compiles a Java probe, without qualifying RN runtime support.
"""

import argparse
import hashlib
import io
import json
import pathlib
import shutil
import subprocess
import sys
import zipfile


# Groovy expressions deliberately preserve types, including present-null vs absent.
CASES = {
    "host34": ("34", 35, False),
    "host35": ("35", 35, False),
    "host36": ("36", 36, False),
    "host37": ("37", 37, False),
    "absent": (None, 35, False),
    "null": ("null", 35, True),
    "invalid": ("'invalid'", 35, True),
    "preview": ("'android-CinnamonBun'", 35, True),
    "fraction": ("36.5", 35, True),
    "fractionString": ("'36.5'", 35, True),
    "boolean": ("true", 35, True),
    "zero": ("0", 35, True),
    "negative": ("-36", 35, True),
    "overflow": ("2147483648L", 35, True),
    "wraparound": ("4294967332L", 35, True),
    "overflowString": ("'2147483648'", 35, True),
    "overflowBigInteger": ("new BigInteger('999999999999999999999999')", 35, True),
    "nan": ("Double.NaN", 35, True),
    "infinity": ("Double.POSITIVE_INFINITY", 35, True),
    "map": ("[api: 36]", 35, True),
    "emptyString": ("''", 35, True),
    "signedString": ("'+36'", 35, True),
    "exponentString": ("'3.6e1'", 35, True),
    "androidPreview": ("'android-37-preview'", 35, True),
    "decimalString": ("'36'", 36, False),
    "paddedString": ("' 0036 '", 36, False),
    "androidString": ("'android-36'", 36, False),
    "android37": ("'android-37'", 37, False),
    "androidOverflow": ("'android-2147483648'", 35, True),
    "androidFraction": ("'android-36.1'", 35, True),
    "gstring": ('"${36}"', 36, False),
    "long": ("36L", 36, False),
    "byte": ("(byte) 36", 36, False),
    "short": ("(short) 36", 36, False),
    "bigInteger": ("new BigInteger('36')", 36, False),
    "integralDecimal": ("36.0", 36, False),
    "integralDouble": ("36.0d", 36, False),
    "maxInt": ("2147483647", 2147483647, False),
    "futureSdk": ("38", 38, False),
}


def quote(value):
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", required=True, type=pathlib.Path)
    parser.add_argument("--react-native-gradle-plugin", required=True, type=pathlib.Path)
    parser.add_argument("--bootstrap-gradle", type=pathlib.Path,
                        help="Build the unchanged RN plugin with its compatible Gradle first")
    parser.add_argument("--work-dir", required=True, type=pathlib.Path)
    parser.add_argument("--agp", default="9.2.1")
    parser.add_argument("--kotlin", default="2.1.20")
    parser.add_argument("--case", action="append", choices=CASES)
    parser.add_argument("--source-build", type=pathlib.Path)
    parser.add_argument("--metadata37", action="store_true",
                        help="Add a synthetic minCompileSdk=37 AAR and compile a Java probe")
    args = parser.parse_args()
    agp_version = tuple(int(n) for n in args.agp.split("."))
    selected = args.case or list(CASES)
    if any(CASES[c][1] == 37 for c in selected) and agp_version < (9, 1, 1):
        parser.error("SDK 37 checks require AGP >= 9.1.1; do not qualify them on older AGP")
    if any(CASES[c][1] == 36 for c in selected) and agp_version < (8, 9, 1):
        parser.error("SDK 36 checks require AGP >= 8.9.1")
    repo = pathlib.Path(__file__).resolve().parents[1]
    source = args.source_build or repo / "packages/react-native/android/build.gradle"
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=False)
    plugin = work / "react-native-gradle-plugin"
    shutil.copytree(args.react_native_gradle_plugin, plugin,
                    ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin"))
    plugin_classpath = "classpath 'com.facebook.react:react-native-gradle-plugin'"
    include_plugin = (f"pluginManagement {{ includeBuild({quote(plugin)}) }}\n"
                      f"includeBuild({quote(plugin)})\n")
    if args.bootstrap_gradle:
        command = [str(args.bootstrap_gradle.resolve()), "-p", str(plugin),
                   ":react-native-gradle-plugin:jar", "--console=plain", "--max-workers=2"]
        with (work / "bootstrap.log").open("w") as log:
            bootstrap = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        if bootstrap.returncode:
            print(json.dumps(dict(phase="bootstrap", exit=bootstrap.returncode, command=command)))
            return bootstrap.returncode
        jars = sorted((plugin / "react-native-gradle-plugin/build/libs").glob("*.jar"))
        jars += sorted((plugin / "shared/build/libs").glob("*.jar"))
        assert len(jars) == 2, jars
        plugin_classpath = ("classpath files(" + ", ".join(quote(p) for p in jars) + ")\n"
                            "classpath 'com.google.code.gson:gson:2.8.9'\n"
                            "classpath 'com.google.guava:guava:31.0.1-jre'\n"
                            "classpath 'com.squareup:javapoet:1.13.0'")
        include_plugin = ""
    (work / "library").mkdir()
    shutil.copy2(source, work / "library/build.gradle")
    source_sha256 = hashlib.sha256((work / "library/build.gradle").read_bytes()).hexdigest()
    (work / "app").mkdir()
    (work / "app/build.gradle").write_text("")
    fixture_setup = ""
    fixture_witness = ""
    if args.metadata37:
        def write_zip(archive, name, value):
            archive.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), value)

        with zipfile.ZipFile(work / "sdk37.aar", "w") as aar:
            write_zip(aar, "AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.sdk37"/>')
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, "w"):
                pass
            write_zip(aar, "classes.jar", jar.getvalue())
            write_zip(aar, "META-INF/com/android/build/gradle/aar-metadata.properties",
                         "aarFormatVersion=1.0\naarMetadataVersion=1.0\nminCompileSdk=37\n"
                         "minCompileSdkExtension=0\nminAndroidGradlePluginVersion=9.1.1\n")
        artifact = work / "repo/test/fixture/sdk37/1.0"
        artifact.mkdir(parents=True)
        shutil.copy2(work / "sdk37.aar", artifact / "sdk37-1.0.aar")
        (artifact / "sdk37-1.0.pom").write_text(
            '<project><modelVersion>4.0.0</modelVersion><groupId>test.fixture</groupId>'
            '<artifactId>sdk37</artifactId><version>1.0</version><packaging>aar</packaging></project>\n')
        java = work / "library/src/main/java/test/SdkProbe.java"
        java.parent.mkdir(parents=True)
        java.write_text("package test; public class SdkProbe { public int sdk() { return android.os.Build.VERSION.SDK_INT; } }\n")
        (work / "library/src/main/AndroidManifest.xml").write_text("<manifest/>\n")
        fixture_setup = """
allprojects {
  repositories { maven { url = rootProject.uri('repo') } }
  configurations.configureEach {
    // Match the unchanged smoke consumer's RN resolution, rather than resolving '+'.
    resolutionStrategy.force 'com.facebook.react:react-android:0.85.3'
  }
}
project(':library') {
  afterEvaluate {
    dependencies.add('implementation', 'test.fixture:sdk37:1.0')
    // This isolated fixture contains a Java probe, no JS package or generated RN bridge.
    tasks.matching { it.name in ['generateCodegenSchemaFromJavaScript', 'generateCodegenArtifactsFromSchema'] }.configureEach { enabled = false }
  }
}
"""
        fixture_witness = """
        println "BOOTCLASSPATH_WITNESS ${dsl.bootClasspath}"
        def metadataFiles = fileTree(project.layout.buildDirectory.dir('intermediates/aar_metadata')).matching { include '**/aar-metadata.properties' }.files
        assert metadataFiles.size() == 1
        def metadata = new Properties()
        metadataFiles.first().withInputStream { metadata.load(it) }
        assert metadata.getProperty('minCompileSdk') == '35'
        println "AAR_WITNESS emittedMinCompileSdk=${metadata.getProperty('minCompileSdk')} injectedDependencyMinCompileSdk=37"
        println 'GRAPH_WITNESS ' + configurations.debugRuntimeClasspath.incoming.resolutionResult.allComponents.collect { it.id.displayName }.sort()
"""
    (work / "settings.gradle").write_text(
        include_plugin +
        "rootProject.name = 'notifykit-compile-sdk-contract'\n"
        "include ':app', ':library'\n")
    (work / "gradle.properties").write_text(
        "android.useAndroidX=true\nandroid.builtInKotlin=false\nandroid.newDsl=false\n"
        "android.builder.sdkDownload=false\n"
        "org.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=768m\n")
    template = """
buildscript {
  repositories { google(); mavenCentral() }
  dependencies {
    classpath 'com.android.tools.build:gradle:AGP_VERSION'
    PLUGIN_CLASSPATH
    classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:KOTLIN_VERSION'
  }
}
HOST_VALUE
FIXTURE_SETUP
def hostBefore = rootProject.ext.properties
project(':library') {
  afterEvaluate {
    tasks.register('verifyCompileSdk') {
      doLast {
        def dsl = project.extensions.getByName('android')
        def actual = dsl.compileSdk
        assert actual == EXPECTED_SDK : "effective compileSdk: expected EXPECTED_SDK, got ${actual}"
        assert com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION == 'AGP_VERSION'
        assert rootProject.ext.has('compileSdkVersion') == hostBefore.containsKey('compileSdkVersion')
        assert rootProject.ext.properties.get('compileSdkVersion').is(hostBefore.get('compileSdkVersion'))
        assert dsl.defaultConfig.minSdkVersion.apiLevel == 24
        assert dsl.defaultConfig.targetSdkVersion.apiLevel == 35
        assert dsl.compileOptions.sourceCompatibility == JavaVersion.VERSION_17
        assert dsl.compileOptions.targetCompatibility == JavaVersion.VERSION_17
        assert dsl.kotlinOptions.jvmTarget == '17'
        assert dsl.defaultConfig.aarMetadata.minCompileSdk == 35
        println "DSL_WITNESS compileSdk=${actual} minSdk=24 targetSdk=35 java=17 kotlin=17 hostUnchanged=true AGP=${com.android.Version.ANDROID_GRADLE_PLUGIN_VERSION} Gradle=${gradle.gradleVersion}"
FIXTURE_WITNESS
      }
    }
  }
}
""".replace("AGP_VERSION", args.agp).replace("KOTLIN_VERSION", args.kotlin).replace(
        "PLUGIN_CLASSPATH", plugin_classpath)
    template = template.replace("FIXTURE_SETUP", fixture_setup).replace("FIXTURE_WITNESS", fixture_witness)
    results = []
    for case in selected:
        expression, expected, warning = CASES[case]
        host = "" if expression is None else f"ext.compileSdkVersion = {expression}"
        (work / "build.gradle").write_text(template.replace("HOST_VALUE", host)
                                            .replace("EXPECTED_SDK", str(expected)))
        tasks = ([":library:checkDebugAarMetadata", ":library:assembleDebug"]
                 if args.metadata37 else [])
        command = [str(args.gradle.resolve()), "-p", str(work), *tasks, ":library:verifyCompileSdk",
                   "--console=plain", "--no-configuration-cache", "--max-workers=2"]
        with (work / f"{case}.log").open("w") as log:
            run = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        output = (work / f"{case}.log").read_text()
        count = output.count("[react-native-notify-kit] Ignoring unusable rootProject.ext.compileSdkVersion")
        passed = run.returncode == 0 and count == int(warning) and "DSL_WITNESS" in output
        result = dict(case=case, expected=expected, warning=warning,
                      warningCount=count, exit=run.returncode, passed=passed, command=command)
        results.append(result)
        print(f"{case}: exit={run.returncode} warnings={count} passed={passed}", flush=True)
    (work / "results.json").write_text(json.dumps(dict(
        source=str(source.resolve()), sha256=source_sha256,
        agp=args.agp, kotlin=args.kotlin, metadata37=args.metadata37,
        scriptSha256=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
        results=results), indent=2) + "\n")
    return 0 if all(r["passed"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
