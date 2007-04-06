/**
 *  Copyright (C) 2005 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.processor.handlers;

import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Handle xforms:group.
 */
public class XFormsGroupHandler extends HandlerBase {

    protected String effectiveGroupId;
    private XFormsControl groupXFormsControl;
    private boolean isFieldsetAppearance;
    private boolean isInternalAppearance;

    public XFormsGroupHandler() {
        super(false, true);
    }

    public void start(String uri, String localname, String qName, Attributes attributes) throws SAXException {

        // Special appearance that does not output any HTML. This is temporary until xforms:group is correctly supported within xforms:repeat.
        isInternalAppearance = XFormsConstants.XXFORMS_INTERNAL_APPEARANCE_QNAME.equals(getAppearance(attributes));
        if (isInternalAppearance)
            return;

        effectiveGroupId = handlerContext.getEffectiveId(attributes);
        isFieldsetAppearance = XFormsConstants.XXFORMS_FIELDSET_APPEARANCE_QNAME.equals(getAppearance(attributes));

        // Find classes to add
        final StringBuffer classes = getInitialClasses(localname, attributes, null);
        if (!handlerContext.isGenerateTemplate()) {
            groupXFormsControl = ((XFormsControl) containingDocument.getObjectById(handlerContext.getPipelineContext(), effectiveGroupId));

            HandlerBase.handleMIPClasses(classes, groupXFormsControl);
        }

        // Start xhtml:span or xhtml:fieldset
        final String groupElementName = isFieldsetAppearance ? "fieldset" : "span";
        final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
        final String groupElementQName = XMLUtils.buildQName(xhtmlPrefix, groupElementName);

        // xforms:label
        final ContentHandler contentHandler = handlerContext.getController().getOutput();
        final String labelValue = (handlerContext.isGenerateTemplate() || groupXFormsControl == null) ? null : groupXFormsControl.getLabel();
        if (isFieldsetAppearance) {

            // Start xhtml:fieldset element
            contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, groupElementName, groupElementQName, getAttributes(attributes, classes.toString(), effectiveGroupId));

            // Output an xhtml:legend element
            final AttributesImpl labelAttributes = getAttributes(attributes, null, null);
            final String legendQName = XMLUtils.buildQName(xhtmlPrefix, "legend");
            contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, "legend", legendQName, labelAttributes);
            if (labelValue != null && !labelValue.equals(""))
                contentHandler.characters(labelValue.toCharArray(), 0, labelValue.length());
            contentHandler.endElement(XMLConstants.XHTML_NAMESPACE_URI, "legend", legendQName);
        } else {
            // Output an xhtml:label element
            // TODO: Should find a way to find xforms:alert/@class
            reusableAttributes.clear();
            reusableAttributes.addAttribute("", "class", "class", ContentHandlerHelper.CDATA, "xforms-label");
            outputLabelFor(handlerContext, reusableAttributes, effectiveGroupId, labelValue);

            // Start xhtml:span element
            contentHandler.startElement(XMLConstants.XHTML_NAMESPACE_URI, groupElementName, groupElementQName, getAttributes(attributes, classes.toString(), effectiveGroupId));
        }

        // NOTE: This doesn't work because attributes for the label are only gathered after start()
//        handleLabelHintHelpAlert(effectiveGroupId, "label", groupXFormsControl);
    }

    public void end(String uri, String localname, String qName) throws SAXException {

        if (isInternalAppearance)
            return;

        // Close xhtml:span
        final String xhtmlPrefix = handlerContext.findXHTMLPrefix();
        final String groupElementName = isFieldsetAppearance ? "fieldset" : "span";
        final String groupElementQName = XMLUtils.buildQName(xhtmlPrefix, groupElementName);
        handlerContext.getController().getOutput().endElement(XMLConstants.XHTML_NAMESPACE_URI, groupElementName, groupElementQName);

        // xforms:help
        handleLabelHintHelpAlert(effectiveGroupId, "help", groupXFormsControl, false);

        // xforms:alert
        handleLabelHintHelpAlert(effectiveGroupId, "alert", groupXFormsControl, false);

        // xforms:hint
        handleLabelHintHelpAlert(effectiveGroupId, "hint", groupXFormsControl, false);
    }
}
