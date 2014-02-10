import de.tototec.sbuild._

@version("0.7.1")
class SBuild(implicit _project: Project) {

  Target("phony:clean") exec {
    Path("target").deleteRecursive
  }

  val compileCp = "mvn:org.scala-lang:scala-library:2.10.3" ~
    "mvn:org.kohsuke:github-api:1.49" ~
    "mvn:de.tototec:de.tototec.cmdoption:0.3.2" ~
    "mvn:commons-lang:commons-lang:2.6" ~
    "mvn:commons-codec:commons-codec:1.7" ~
    "mvn:com.fasterxml.jackson.core:jackson-databind:2.2.3" ~
    "mvn:com.fasterxml.jackson.core:jackson-annotations:2.2.3" ~
    "mvn:com.fasterxml.jackson.core:jackson-core:2.2.3" ~
    "mvn:commons-io:commons-io:1.4" ~
    "mvn:com.infradna.tool:bridge-method-annotation:1.8"

  val redmineUrl = "http://sbuild.tototec.de/sbuild"
  val issueDir = Path("target/issues")
  val maxIssues = 176
  val githubRepo = "SBuild-org/sbuild"

  ExportDependencies("eclipse.classpath", compileCp)

  Target("phony:print-classpath") dependsOn compileCp exec {
    println(compileCp.files.mkString(":"))
  }

  Target("phony:clean").evictCache exec {
    Path("target").deleteRecursive
  }

  import addons.scala._

  Target("phony:fetch-issues") exec {
    issueDir.mkdirs
    1.to(176).foreach { i =>
      addons.support.ForkSupport.runAndWait(
        command = Array("wget", "-O", (issueDir / s"$i.xml").getPath, s"${redmineUrl}/issues/$i.xml?include=journals,relations,attachments"),
        failOnError = true
      )
    }
  }

  Target("phony:compile").cacheable dependsOn Scalac.compilerClasspath("2.10.3") ~ compileCp ~ "scan:src/main/scala" exec {
    Scalac(
      compilerClasspath = Scalac.compilerClasspath("2.10.3").files,
      classpath = compileCp.files,
      sources = "scan:src/main/scala".files,
      destDir = Path("target/classes")
    )
  }

  Target("phony:import-sbuild-tickets") dependsOn "compile" ~ compileCp exec {
    addons.support.ForkSupport.runJavaAndWait(
      classpath = compileCp.files ++ Seq(Path("target/classes")),
      arguments = Array(
        "de.tobiasroeser.redminetogithub.Main",
        "--redmine-issues-dir", issueDir.getPath,
        "--github-user", "lefou",
        "--github-repo", githubRepo,
        "--redmine-url", redmineUrl,
        "--user-mapping", "3=lefou",
        "--user-mapping", "4=tlahn",
        "--user-mapping", "6=h2000",
        "--user-mapping", "160=sirinath"
      ),
      interactive = true,
      failOnError = true
    )
  }

}
