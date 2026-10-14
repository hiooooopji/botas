plugins { base }

tasks.register("build18") {
    group = "build"
    description = "Build and check Botas for Minecraft 1.8."
    dependsOn(gradle.includedBuild("botas-1.8").task(":build"))
}
tasks.register("build1710") {
    group = "build"
    description = "Build and check the retained Minecraft 1.7.10 port."
    dependsOn(gradle.includedBuild("botas-1.7.10").task(":build"))
}
tasks.named("build") { dependsOn("build18", "build1710") }
tasks.named("check") {
    dependsOn(gradle.includedBuild("botas-1.8").task(":check"))
    dependsOn(gradle.includedBuild("botas-1.7.10").task(":check"))
}
tasks.register("runClient") {
    group = "application"
    dependsOn(gradle.includedBuild("botas-1.8").task(":runClient"))
}
