/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr

import org.orbeon.oxf.properties.Properties
import org.orbeon.oxf.xml._
import org.orbeon.scaxon.XML._
import scala.collection.JavaConverters._
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.ScalaUtils._
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.oxf.util.{NetUtils, XPathCache}
import org.orbeon.oxf.pipeline.InitUtils

object FormRunner {

    val propertyPrefix = "oxf.fr.authentication."

    val methodPropertyName = propertyPrefix + "method"
    val containerRolesPropertyName = propertyPrefix + "container.roles"
    val headerUsernamePropertyName = propertyPrefix + "header.username"
    val headerRolesPropertyName = propertyPrefix + "header.roles"

    type UserRoles = {
        def getRemoteUser(): String
        def isUserInRole(role: String): Boolean
    }

    /**
     * Get the username and roles from the request, based on the Form Runner configuration.
     */
    def getUserRoles(userRoles: UserRoles, getHeader: String => Option[Array[String]]): (Option[String], Option[Array[String]]) = {

        val propertySet = Properties.instance.getPropertySet
        propertySet.getString(methodPropertyName, "container") match {
            case "container" =>

                val rolesString = propertySet.getString(containerRolesPropertyName)

                assert(rolesString != null)

                val username = Option(userRoles.getRemoteUser)

                val rolesArray = (
                    for {
                        role <- rolesString.split(""",|\s+""")
                        if userRoles.isUserInRole(role)
                    } yield
                        role)

                val roles = rolesArray match {
                    case Array() => None
                    case array => Some(array)
                }

                (username, roles)

            case "header" =>

                def headerOption(name: String) = Option(propertySet.getString(name)) flatMap (p => getHeader(p.toLowerCase))

                val username = headerOption(headerUsernamePropertyName) map (_.head)
                val roles = headerOption(headerRolesPropertyName) map (_ flatMap (_.split("""(\s*[,\|]\s*)+""")))

                (username, roles)

            case other => throw new OXFException("Unsupported authentication method, check the '" + methodPropertyName + "' property:" + other)
        }
    }

    def getUserRolesAsHeaders(userRoles: UserRoles, getHeader: String => Option[Array[String]]) = {

        val (username, roles) = FormRunner.getUserRoles(userRoles, getHeader)

        val result = collection.mutable.Map[String, Array[String]]()

        username foreach (u => result += ("orbeon-username" -> Array(u)))
        roles foreach (r => result += ("orbeon-roles" -> r))

        result.toMap
    }

    def getPersistenceURLHeaders(app: String, form: String, formOrData: String) = {

        require(augmentString(app).nonEmpty)
        require(augmentString(form).nonEmpty)
        require(Set("form", "data")(formOrData))

        val propertySet = Properties.instance.getPropertySet

        // Find provider
        val provider = {
            val providerProperty = Seq("oxf.fr.persistence.provider", app, form, formOrData) mkString "."
            propertySet.getString(providerProperty)
        }

        getPersistenceURLHeadersFromProvider(provider)
    }

    def getPersistenceURLHeadersFromProvider(provider: String) = {
        val propertySet = Properties.instance.getPropertySet

        // Find provider URI
        def findProviderURL = {
            val uriProperty = Seq("oxf.fr.persistence", provider, "uri") mkString "."
            propertySet.getStringOrURIAsString(uriProperty)
        }

        val propertyPrefix = "oxf.fr.persistence." + provider

        // Build headers map
        val headers = (
            for {
                propertyName <- propertySet.getPropertiesStartsWith(propertyPrefix).asScala
                lowerSuffix = propertyName.substring(propertyPrefix.length + 1)
                if lowerSuffix != "uri"
                headerName = "Orbeon-" + capitalizeHeader(lowerSuffix)
                headerValue = propertySet.getObject(propertyName).toString
            } yield
                (headerName -> headerValue)) toMap

        (findProviderURL, headers)
    }

    def getPersistenceHeadersAsXML(app: String, form: String, formOrData: String) = {

        val (_, headers) = getPersistenceURLHeaders(app, form, formOrData)

        // Build headers document
        val headersXML =
            <headers>{
                for {
                    (name, value) <- headers
                } yield
                    <header><name>{XMLUtils.escapeXMLMinimal(name)}</name><value>{XMLUtils.escapeXMLMinimal(value)}</value></header>
            }</headers>.toString

        // Convert to TinyTree
        TransformerUtils.stringToTinyTree(XPathCache.getGlobalConfiguration, headersXML, false, false)
    }

    /**
     * Given the metadata for a form, returns the sequence of operations that the current user is authorized to perform.
     * The sequence can contain just the "*" string to denote that the user is allowed to perform any operation.
     */
    def authorizedOperationsOnForm(metadata: NodeInfo): java.util.List[String] = {
        val request = NetUtils.getExternalContext.getRequest
        (metadata \ "permissions" match {
            case Seq() => Seq("*")                                                      // No permissions defined for this form, authorize any operation
            case ps => ( ps \ "permission"
                    filter (p =>
                        (p \ * isEmpty) ||                                              // No constraint on the permission, so it is automatically satisfied
                        (p \ "user-role" forall (r =>                                   // If we have user-role constraints, they must all pass
                            (r \@ "any-of" stringValue) split "\\s+"                 // Constraint is satisfied if user has at least one of the roles
                            map (_.replace("%20", " "))                                 // Unescape internal spaces as the roles used in Liferay are user-facing labels that can contain space (see also permissions.xbl)
                            exists (request.isUserInRole(_)))))
                    flatMap (p => (p \@ "operations" stringValue) split "\\s+")      // For the permissions that passed, return the list operations
                    distinct                                                            // Remove duplicate operations
                )
        }) asJava
    }

    // Interrupt current processing and send an error code to the client.
    // NOTE: This could be done through ExternalContext
    def sendError(code: Int) = InitUtils.sendError(code)
}