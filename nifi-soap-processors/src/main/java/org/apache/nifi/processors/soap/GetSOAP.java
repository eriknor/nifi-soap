/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.soap;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.OMNamespace;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.addressing.EndpointReference;
import org.apache.axis2.client.Options;
import org.apache.axis2.client.ServiceClient;
import org.apache.commons.httpclient.Header;

import org.apache.axis2.transport.http.HTTPConstants;
import org.apache.axis2.transport.http.impl.httpclient3.HttpTransportPropertiesImpl;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ProcessorLog;
import org.apache.nifi.processor.*;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StopWatch;
import org.apache.axis2.transport.http.HttpTransportProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

@SupportsBatching
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@Tags({"SOAP", "Get", "Ingest", "Ingress"})
@CapabilityDescription("Execute provided request against the SOAP endpoint. The result will be left in it's orginal form. " +
        "This processor can be scheduled to run on a timer, or cron expression, using the standard scheduling methods, " +
        "or it can be triggered by an incoming FlowFile. If it is triggered by an incoming FlowFile, then attributes of " +
        "that FlowFile will be available when evaluating the executing the SOAP request.")
@WritesAttribute(attribute = "mime.type", description = "Sets mime type to text/xml")
@DynamicProperty(name = "The name of a input parameter the needs to be passed to the SOAP method being invoked.",
        value = "The value for this parameter '=' and ',' are not considered valid values and must be escpaed . Note, if the value of parameter needs to be an array the format should be key1=value1,key2=value2.  ",
        description = "The name provided will be the name sent in the SOAP method, therefore please make sure " +
                "it matches the wsdl documentation for the SOAP service being called. In the case of arrays " +
                "the name will be the name of the array and the key's specified in the value will be the element " +
                "names pased.")

public class GetSOAP extends AbstractProcessor {


    protected static final PropertyDescriptor ENDPOINT_URL = new PropertyDescriptor
            .Builder()
            .name("Endpoint URL")
            .description("The endpoint url that hosts the web service(s) that should be called.")
            .required(true)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    protected static final PropertyDescriptor WSDL_URL = new PropertyDescriptor
            .Builder()
            .name("WSDL URL")
            .description("The url where the wsdl file can be retrieved and referenced.")
            .required(true)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    protected static final PropertyDescriptor METHOD_NAME = new PropertyDescriptor
            .Builder()
            .name("SOAP Method Name")
            .description("The method exposed by the SOAP webservice that should be invoked.")
            .required(true)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor USER_NAME = new PropertyDescriptor
            .Builder()
            .name("User name")
            .sensitive(true)
            .description("The username to use in the case of basic Auth")
            .required(false)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor PASSWORD = new PropertyDescriptor
            .Builder()
            .name("Password")
            .sensitive(true)
            .description("The password to use in the case of basic Auth")
            .required(false)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();


    protected static final PropertyDescriptor USER_AGENT = new PropertyDescriptor
            .Builder()
            .name("User Agent")
            .defaultValue("NiFi SOAP Processor")
            .description("The user agent string to use, the default is Nifi SOAP Processor")
            .required(false)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor HEADER = new PropertyDescriptor
            .Builder()
            .name("Headers")
            .defaultValue(null)
            .description("Extra SOAP headers to use, formatted 'name:value name1:value1 name2:value2")
            .required(false)
            .expressionLanguageSupported(false)
        //    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    protected static final PropertyDescriptor PROXY_NAME = new PropertyDescriptor
            .Builder()
    		.name("Proxy Host")
			.description("The fully qualified hostname or IP address of the proxy server")
			.required(false)
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.build();

	public static final PropertyDescriptor PROXY_PORT = new PropertyDescriptor
			.Builder()
			.name("Proxy Port")
			.description("The port of the proxy server")
			.required(false)
			.addValidator(StandardValidators.PORT_VALIDATOR)
			.build();
    protected static final PropertyDescriptor PROXY_USER = new PropertyDescriptor
            .Builder()
            .name("Proxy Username")
            .defaultValue(null)
            .description("Proxy username if required.")
            .required(false)
            .expressionLanguageSupported(false)
        //    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();
    protected static final PropertyDescriptor PROXY_PWD = new PropertyDescriptor
            .Builder()
            .name("Proxy password")
            .defaultValue(null)
            .description("Proxy password")
            .required(false)
            .expressionLanguageSupported(false)
        //    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();


    protected static final PropertyDescriptor SO_TIMEOUT = new PropertyDescriptor
            .Builder()
            .name("Socket Timeout")
            .defaultValue("60000")
            .description("The timeout value to use waiting for data from the webservice")
            .required(false)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    protected static final PropertyDescriptor CONNECTION_TIMEOUT = new PropertyDescriptor
            .Builder()
            .name("Connection Timeout")
            .defaultValue("60000")
            .description("The timeout value to use waiting to establish a connection to the web service")
            .required(false)
            .expressionLanguageSupported(false)
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();


    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All FlowFiles that are created are routed to this relationship")
            .build();


    private List<PropertyDescriptor> descriptors;

    private ServiceClient serviceClient;

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(ENDPOINT_URL);
        descriptors.add(WSDL_URL);
        descriptors.add(METHOD_NAME);
        descriptors.add(USER_NAME);
        descriptors.add(PASSWORD);
        descriptors.add(USER_AGENT);
        descriptors.add(HEADER);
        descriptors.add(SO_TIMEOUT);
        descriptors.add(CONNECTION_TIMEOUT);
        descriptors.add(PROXY_NAME);
        descriptors.add(PROXY_PORT);
        descriptors.add(PROXY_USER);
        descriptors.add(PROXY_PWD);

        this.descriptors = Collections.unmodifiableList(descriptors);

    }

    @Override
    public Set<Relationship> getRelationships() {
        final Set<Relationship> relationships = new HashSet<>(1);
        relationships.add(REL_SUCCESS);
        return relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .description("Specifies the method name and parameter names and values for '" + propertyDescriptorName + "' the SOAP method being called.")
                .name(propertyDescriptorName).addValidator(StandardValidators.NON_EMPTY_VALIDATOR).dynamic(true)
                .build();
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        Options options = new Options();

        final String endpointURL = context.getProperty(ENDPOINT_URL).getValue();
        options.setTo(new EndpointReference(endpointURL));

        if (isHTTPS(endpointURL)) {
            options.setTransportInProtocol(Constants.TRANSPORT_HTTPS);
        } else {
            options.setTransportInProtocol(Constants.TRANSPORT_HTTP);
        }

        options.setCallTransportCleanup(true);
        options.setProperty(HTTPConstants.CHUNKED, false);

        if(context.getProperty(HEADER).getValue()!=null&&!"".equals(context.getProperty(HEADER).getValue().trim())){
        	try{
        	//set custom headers
    		List list = new ArrayList();  
        	String[] headers = context.getProperty(HEADER).getValue().split(" ");
        	for(String header:headers){
        		String[] nv = header.split(":");
        		  
        		// Create an instance of org.apache.commons.httpclient.Header  
        		Header h = new Header();  
        		  
        		// Http header. Name : user, Value : admin  
        		h.setName(nv[0]);  
        		h.setValue(nv[1]);  
        		    
        		list.add(h);  
        	}
    		options.setProperty(org.apache.axis2.transport.http.HTTPConstants.HTTP_HEADERS, list); 
        	}catch(Exception e){
        		getLogger().error("Failed to parse headers value. "+e);
                throw new ProcessException(e);
        	}
        }

		if(context.getProperty(PROXY_NAME).getValue()!=null&&!"".equals(context.getProperty(PROXY_NAME).getValue().trim())){
			HttpTransportProperties.ProxyProperties pp = new HttpTransportProperties.ProxyProperties();
 			pp.setProxyName(context.getProperty(PROXY_NAME).getValue());
 			pp.setProxyPort(Integer.parseInt(context.getProperty(PROXY_PORT).getValue()));
			if(context.getProperty(PROXY_USER).getValue()!=null&&!"".equals(context.getProperty(PROXY_USER).getValue().trim())){
 				pp.setUserName(context.getProperty(PROXY_USER).getValue());
			}
			if(context.getProperty(PROXY_PWD).getValue()!=null&&!"".equals(context.getProperty(PROXY_PWD).getValue().trim())){
 				pp.setPassWord(context.getProperty(PROXY_PWD).getValue());
			}
 			options.setProperty(HTTPConstants.PROXY,pp);
     	}   
        options.setProperty(HTTPConstants.USER_AGENT, context.getProperty(USER_AGENT).getValue());
        options.setProperty(HTTPConstants.SO_TIMEOUT, context.getProperty(SO_TIMEOUT).asInteger());
        options.setProperty(HTTPConstants.CONNECTION_TIMEOUT, context.getProperty(CONNECTION_TIMEOUT).asInteger());
        //get the username and password -- they both must be populated.

        final String userName = context.getProperty(USER_NAME).getValue();
        final String password = context.getProperty(PASSWORD).getValue();
        if (null != userName && null != password && !userName.isEmpty() && !password.isEmpty()) {

            HttpTransportPropertiesImpl.Authenticator
                    auth = new HttpTransportPropertiesImpl.Authenticator();
            auth.setUsername(userName);
            auth.setPassword(password);
            options.setProperty(org.apache.axis2.transport.http.HTTPConstants.AUTHENTICATE, auth);
        }
        try {
            serviceClient = new ServiceClient();
           serviceClient.setOptions(options);
        } catch (AxisFault axisFault) {
            getLogger().error("Failed to create webservice client, please check that the service endpoint is available and " +
                    "the property is valid.", axisFault);
            throw new ProcessException(axisFault);
        }
    }

    @OnStopped
    public void onStopped(final ProcessContext context) {
        try {
            serviceClient.cleanup();
        } catch (AxisFault axisFault) {
            getLogger().error("Failed to clean up the web service client.", axisFault);
            throw new ProcessException(axisFault);
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {

        final StopWatch stopWatch = new StopWatch(true);
        //get the dynamic properties, execute the call and return the results
        OMFactory fac = OMAbstractFactory.getOMFactory();
        OMNamespace omNamespace = fac.createOMNamespace(context.getProperty(WSDL_URL).getValue(), "nifi");

        final OMElement method = getSoapMethod(fac, omNamespace, context.getProperty(METHOD_NAME).getValue());

        //now we need to walk the arguments and add them
        addArgumentsToMethod(context, fac, omNamespace, method);
        final OMElement result = executeSoapMethod(method);
        final FlowFile flowFile = processSoapRequest(session, result);
        session.transfer(flowFile, REL_SUCCESS);

    }

    FlowFile processSoapRequest(ProcessSession session, final OMElement result) {

        FlowFile intermediateFlowFile = session.create();

        intermediateFlowFile = session.write(intermediateFlowFile, new OutputStreamCallback() {
            @Override
            public void process(final OutputStream out) throws IOException {
                try {
                    String response = result.getFirstElement().getText();
                    out.write(response.getBytes());
                } catch (AxisFault axisFault) {
                    final ProcessorLog logger = getLogger();
                    if (null != logger)
                        logger.error("Failed parsing the data that came back from the web service method", axisFault);
                    throw new ProcessException(axisFault);
                }
            }
        });

        final Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.MIME_TYPE.key(), "application/xml");
        return session.putAllAttributes(intermediateFlowFile, attributes);
    }

    OMElement executeSoapMethod(OMElement method) {
        try {
            return serviceClient.sendReceive(method);
        } catch (AxisFault axisFault) {
            final ProcessorLog logger = getLogger();
            if (null != logger)
                logger.error("Failed invoking the web service method", axisFault);
            throw new ProcessException(axisFault);
        }
    }

    void addArgumentsToMethod(ProcessContext context, OMFactory fac, OMNamespace omNamespace, OMElement method) {
        final ProcessorLog logger = getLogger();
        for (final Map.Entry<PropertyDescriptor, String> entry : context.getProperties().entrySet()) {
            PropertyDescriptor descriptor = entry.getKey();
            if (descriptor.isDynamic()) {
                if (null != logger)
                    logger.debug("Processing dynamic property: " + descriptor.getName() + " with value: " + entry.getValue());
                OMElement value = getSoapMethod(fac, omNamespace, descriptor.getName());
                value.addChild(fac.createOMText(value, entry.getValue()));
                method.addChild(value);
            }
        }
    }

    OMElement getSoapMethod(OMFactory fac, OMNamespace omNamespace, String value) {
        return fac.createOMElement(value, omNamespace);
    }

    private static boolean isHTTPS(final String url) {
        return url.charAt(4) == ':';
    }
}
