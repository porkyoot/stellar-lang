dependencies {
    implementation(project(":stellar-core"))
    implementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    include("com.microsoft.onnxruntime:onnxruntime:1.20.0")
    compileOnly("com.terraformersmc:modmenu:11.0.3-local")
    compileOnly("me.shedaniel.cloth:cloth-config-fabric:15.0.140-local")
    testImplementation("com.terraformersmc:modmenu:11.0.3-local")
    testImplementation("me.shedaniel.cloth:cloth-config-fabric:15.0.140-local")
}

tasks.named<Jar>("jar") {
    from(project(":stellar-core").the<SourceSetContainer>()["main"].output)
    from(provider {
        configurations.named("runtimeClasspath").get()
            .filter { it.name.startsWith("onnxruntime") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) {
                exclude("**/mixin/**", "**/LangClothConfigScreen*", "**/StellarLangModMenu*", "**/SignTooltipRenderer*", "**/TranslatedBookWidget*")
            }
        }
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(
        classDirectories.files.map {
            fileTree(it) {
                exclude("**/mixin/**", "**/LangClothConfigScreen*", "**/StellarLangModMenu*", "**/SignTooltipRenderer*", "**/TranslatedBookWidget*")
            }
        }
    )
    violationRules {
        rule {
            limit {
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

