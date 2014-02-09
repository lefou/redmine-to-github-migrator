import de.tototec.sbuild._

@version("0.7.1")
class SBuild(implicit _project: Project) {

  Target("phony:clean") exec {
    Path("target").deleteRecursive
  }

  val compileCp = "mvn:org.scala-lang:scala-library:2.10.3" ~
    "mvn:org.kohsuke:github-api:1.49" ~
    "mvn:de.tototec:de.tototec.cmdoption:0.3.2"

  ExportDependencies("eclipse.classpath", compileCp)

  Target("phony:print-classpath") dependsOn compileCp exec {
    println(compileCp.files.mkString(":"))
  }
  
}
