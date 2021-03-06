/*
 * Copyright 2014 The Guardian
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import play.api.mvc._
import lib._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import play.api.libs.ws.WS
import org.kohsuke.github.GitHub
import collection.convert.wrapAsScala._
import play.api.libs.json.JsString
import scala.Some
import play.api.Logger
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.Form
import java.io.IOException
import scala.util.{Failure, Success, Try}
import lib.Implicits._


object Application extends Controller {

  def audit(orgName: String) = Action.async { implicit req =>
    val auditDef = AuditDef.safelyCreateFor(orgName, apiKeyFor(req).get)

    Logger.info(s"Asked to audit ${auditDef.org.atLogin}")

    auditDef.ensureSeemsLegit()

    for (orgSnapshot <- OrgSnapshot(auditDef)) yield {
      Logger.info(s"availableRequirementEvaluators=${orgSnapshot.availableRequirementEvaluators} ${orgSnapshot.orgUserProblemStats}")
      orgSnapshot.createIssuesForNewProblemUsers()

      orgSnapshot.updateExistingAssignedIssues()

      orgSnapshot.closeUnassignedIssues()

      Ok(views.html.userPages.results(auditDef, orgSnapshot))
    }
  }

  import GithubAppConfig._
  val ghAuthUrl = s"${authUrl}?client_id=${clientId}&scope=${scope}"

  def index = Action { implicit req =>
    Ok(views.html.userPages.index(ghAuthUrl, apiKeyForm))
  }

  val apiKeyForm = Form("apiKey" -> of[String])

  def oauthCallback(code: String) = Action.async {
    import GithubAppConfig._
    val resFT = WS.url(accessTokenUrl)
                  .withQueryString(("code", code),("client_id", clientId),("client_secret", clientSecret))
                  .withHeaders(("Accept", "application/json"))
                  .post("")

    resFT.map{ res =>
      res.json \ "access_token" match {
        case JsString(accessCode) => Redirect("/choose-your-org").withSession("userId" -> accessCode)
        case _ => Redirect("/")
      }
    }
  }

  def storeApiKey() = Action { implicit req =>
   apiKeyForm.bindFromRequest().fold(
     formWithErrors =>  Ok(views.html.userPages.index(ghAuthUrl, formWithErrors)),
     accessToken => {
       try {
         GitHub.connectUsingOAuth(accessToken)
         Redirect("/choose-your-org").withSession("userId" -> accessToken)
       } catch {
         case e: IOException => {
           Ok(views.html.userPages.index(ghAuthUrl, apiKeyForm, Some("there was a problem with the key you supplied")))
         }
       }
     }
   )
  }

  def chooseYourOrg = Action { implicit req =>
    apiKeyFor(req) match {
      case Success(accessToken) => {
        val conn = GitHub.connectUsingOAuth(accessToken)
        val orgs = conn.getMyOrganizations().values().toList
        val user = conn.getMyself
        Ok(views.html.userPages.orgs(orgs, user))
      }
      case Failure(_) => Ok(views.html.userPages.index(ghAuthUrl, apiKeyForm, Some("You must supply GitHub authentication to audit your org")))
    }
  }

  def apiKeyFor(req: RequestHeader) = Try {
    req.session.get("userId").getOrElse(req.headers("Authorization").split(' ')(1))
  }
}
