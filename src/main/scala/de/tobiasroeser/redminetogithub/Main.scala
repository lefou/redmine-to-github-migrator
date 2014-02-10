package de.tobiasroeser.redminetogithub

import org.kohsuke.github._
import de.tototec.cmdoption._
import java.io.File
import java.util.Scanner
import java.io.LineNumberReader
import java.io.FileReader
import java.io.BufferedReader
import scala.xml.XML
import scala.xml.Elem
import scala.xml.Node

object Main {

  class Config {
    @CmdOption(names = Array("--redmine-url"), args = Array("URL"))
    var redmineUrl: String = ""

    @CmdOption(names = Array("--redmine-issues-dir"), args = Array("FILE"), minCount = 1)
    var redmineIssuesDir: String = _

    @CmdOption(names = Array("--user-mapping"), args = Array("redmine-user-id=github-user-id"), maxCount = -1)
    def addUserMapping(mapping: String): Unit = mapping.split("=", 2) match {
      case Array(r, g) => userMapping += ((r, g))
      case _ => throw new RuntimeException("Unsupported user mapping: " + mapping)
    }
    var userMapping: Map[String, String] = Map()

    @CmdOption(names = Array("--github-user"), args = Array("LOGIN"))
    var githubUser: String = _
    @CmdOption(names = Array("--github-password"), args = Array("PASSWORD"))
    def setGithubPassword(string: String) = githubPassword = string.toCharArray
    var githubPassword: Array[Char] = _

    @CmdOption(names = Array("--github-repo"), args = Array("REPO"))
    var githubRepo: String = _

  }

  def main(args: Array[String]): Unit = {
    val config = new Config()
    val help = new {
      @CmdOption(names = Array("--help", "-h"), isHelp = true)
      var help: Boolean = _
    }
    val parser = new CmdlineParser(config, help)
    parser.parse(args: _*)

    if (help.help) {
      parser.usage()
      return
    }

    if (config.githubUser != null && config.githubPassword == null) {
      print(s"Github password for '${config.githubUser}': ")
      config.githubPassword = System.console().readPassword()
    }

    val dir = new File(config.redmineIssuesDir)
    val files = dir.listFiles.filter(_.getName.endsWith(".xml")).toSeq

    val issues = files.map { file =>
      val xml = XML.loadFile(file)
      new Issue(xml)
    }.sortBy(_.id)

    println("read xml: " + issues.mkString("\n==========\n"))

    if (config.githubUser == null || config.githubPassword == null || config.githubRepo == null) {
      println("Skipping publishing to github")
    } else {
      pushIssuesToGithub(config.githubUser, config.githubPassword, config.githubRepo, issues, config.redmineUrl, config.userMapping)
    }

  }

  def pushIssuesToGithub(ghUser: String, ghPass: Array[Char], repoName: String, issues: Seq[Issue], redmineUrl: String, redmineUserMap: Map[String, String]): Unit = {
    val gh = GitHub.connectUsingPassword(ghUser, ghPass.mkString)
    val repo = gh.getRepository(repoName)

    // Milestones
    var redmineMilestones: Map[Int, String] = Map()
    issues.map { ticket =>
      (ticket.milestoneId, ticket.milestone) match {
        case (Some(i), Some(m)) => redmineMilestones += i -> m
        case _ =>
      }
    }

    val githubMilestones = redmineMilestones.map { milestone =>
      println(s"Creating Milestone: ${milestone._2}")
      milestone._1 -> {
        val m = repo.createMilestone(milestone._2, milestone._2)
        println(s"Created: ${m}")
        m
      }
    }

    def githubUser(rId: Int) = redmineUserMap.get(rId.toString)
    def formatUser(user: String, id: Int): String = s"[${user}](${redmineUrl}/users/${id})" + githubUser(id).map(u => s" => @${u}").getOrElse("")

    issues.map { ticket =>

      val issue = repo.createIssue(ticket.subject)

      val relations = ticket.relations.map(rel => rel.printRelativeTo(ticket.id)).mkString("\n\n")

      val text = s"""${ticket.description}
                    |
                    |**Ticket imported form Redmine ${redmineUrl}**
                    | 
                    |**Original Redmine issue link:** [Redmine ID ${ticket.id}](${redmineUrl}/issues/${ticket.id})
                    |**Reporter:** ${formatUser(ticket.author, ticket.authorId)}
                    |**Creating date:** ${ticket.createdOn}
                    | 
                    |**Assigned to:** ${ticket.assignedTo.map(_ => formatUser(ticket.assignedTo.get, ticket.assignedToId.get)).getOrElse("")}
                    |**Start date:** ${ticket.startDate}
                    |**Due date:** ${ticket.endDate}
                    |
                    | ${relations}
                    |
                    |""".stripMargin

      issue.body(text)
      ticket.assignedToId.flatMap(i => githubUser(i)).map(u => issue.assignee(u))
      ticket.milestoneId.flatMap(m => githubMilestones.get(m)).map(m => issue.milestone(m))

      ticket.tracker match {
        case "Bug" => issue.label("bug")
        case "Feature" => issue.label("enhancement")
        case "Support" => issue.label("question")
        case _ =>
      }
      if (ticket.status == "Rejected") issue.label("invalid")

      println(s"Creating issue #${ticket.id}")
      val created = issue.create()
      println(s"Created: ${created}")

      // Comments

      ticket.journals.map { journal =>
        println(s"Creating issue comment #${ticket.id}-${journal.id}")

        val details = journal.details.map { detail =>
          s"""* **${detail.name} changed**
           |  * **Old Value:** ${detail.oldValue}
           |  * **New Value:** ${detail.newValue}
           """.stripMargin
        }.mkString("\n")

        val text = s"""${journal.notes}
                      |
                      | ${details}
                      |
                      |**Comment imported from Redmine ${redmineUrl}/issues/${ticket.id}**
                      |**Author:** ${formatUser(journal.author, journal.authorId)}
                      |**Creating date:** ${journal.createdOn}
                      """.stripMargin

        created.comment(text)
      }

      // close ticket
      val closed = Seq("Closed", "Rejected")

      if (closed.contains(ticket.status)) {
        println(s"Closing ticket #${ticket.id}")
        created.close()
      }
    }

  }

}

class IssueJournalDetail(xml: Node) {
  val property = (xml \ "@property").text
  val name = (xml \ "@name")
  val oldValue = (xml \ "old_value").text
  val newValue = (xml \ "new_value").text
}

class IssueJournal(xml: Node) {
  val id = (xml \ "@id").text.toInt
  val createdOn = (xml \ "created_on").text
  val author = (xml \ "user" \ "@name").text
  val authorId = (xml \ "user" \ "@id").text.toInt
  val notes = (xml \ "notes").text
  val details = (xml \ "details" \ "detail").map { xml => new IssueJournalDetail(xml) }

  override def toString = getClass.getSimpleName +
    "(id=" + id +
    ",createdOn=" + createdOn +
    ",author=" + author +
    ",authorId=" + authorId +
    ",notes=" + notes +
    ")"
}

class IssueRelation(xml: Node) {
  val issue = (xml \ "@issue_id").text.toInt
  val relatedIssue = (xml \ "@issue_to_id").text.toInt
  val relationType = (xml \ "@relation_type").text

  def printRelativeTo(thisIssueId: Int) = if (issue == thisIssueId) {
    s"This issue ${relationType} issue #${relatedIssue}."
  } else {
    s"Another issue #${issue} ${relationType} this issue."
  }
}

class Issue(xml: Node) {
  val id = (xml \ "id").text.toInt
  val projectId = (xml \ "project" \ "@id").text.toInt
  val tracker = (xml \ "tracker" \ "@name").text
  val status = (xml \ "status" \ "@name").text
  val author = (xml \ "author" \ "@name").text
  val authorId = (xml \ "author" \ "@id").text.toInt
  val category = (xml \ "category" \ "@name").text
  val startDate = (xml \ "start_date").text
  val endDate = (xml \ "due_date").text
  val subject = (xml \ "subject").text
  val description = (xml \ "description").text
  val createdOn = (xml \ "created_on").text
  //  val assignedTo = ???
  val journals = (xml \ "journals" \ "journal").map { xml => new IssueJournal(xml) }.sortBy(_.id)
  val milestone = (xml \ "fixed_version") match {
    case x if x.isEmpty => None
    case x => Some((x \ "@name").text)
  }
  val milestoneId = (xml \ "fixed_version") match {
    case x if x.isEmpty => None
    case x => Some((x \ "@id").text.toInt)
  }
  val assignedTo = (xml \ "assigned_to") match {
    case x if x.isEmpty => None
    case x => Some((x \ "@name").text)
  }
  val assignedToId = (xml \ "assigned_to") match {
    case x if x.isEmpty => None
    case x => Some((x \ "@id").text.toInt)
  }
  val relations = (xml \ "relations" \ "relation").map { xml => new IssueRelation(xml) }

  override def toString = getClass.getSimpleName() +
    "(id=" + id +
    ",projectId=" + projectId +
    ",tracker=" + tracker +
    ",status=" + status +
    ",author=" + author +
    ",authorId=" + authorId +
    ",category=" + category +
    ",startDate=" + startDate +
    ",endDate=" + endDate +
    ",subject=" + subject +
    ",description=" + description +
    ",createdOn=" + createdOn +
    ",journals=" + journals +
    ",milestone=" + milestone +
    ",milestoneId=" + milestoneId +
    ",assignedTo=" + assignedTo +
    ",assignedToId=" + assignedToId +
    ")"
}
