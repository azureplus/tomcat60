/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.catalina.valves;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * <p>
 * Tomcat port of <a href="http://httpd.apache.org/docs/trunk/mod/mod_remoteip.html">mod_remoteip</a>, this valve replaces the apparent
 * client remote IP address and hostname for the request with the IP address list presented by a proxy or a load balancer via a request
 * headers (e.g. "X-Forwarded-For").
 * </p>
 * <p>
 * Another feature of this valve is to replace the apparent scheme (http/https) and server port with the scheme presented by a proxy or a
 * load balancer via a request header (e.g. "X-Forwarded-Proto").
 * </p>
 * <p>
 * This valve proceeds as follows:
 * </p>
 * <p>
 * If the incoming <code>request.getRemoteAddr()</code> matches the valve's list of internal proxies :
 * <ul>
 * <li>Loop on the comma delimited list of IPs and hostnames passed by the preceding load balancer or proxy in the given request's Http
 * header named <code>$remoteIpHeader</code> (default value <code>x-forwarded-for</code>). Values are processed in right-to-left order.</li>
 * <li>For each ip/host of the list:
 * <ul>
 * <li>if it matches the internal proxies list, the ip/host is swallowed</li>
 * <li>if it matches the trusted proxies list, the ip/host is added to the created proxies header</li>
 * <li>otherwise, the ip/host is declared to be the remote ip and looping is stopped.</li>
 * </ul>
 * </li>
 * <li>If the request http header named <code>$protocolHeader</code> (e.g. <code>x-forwarded-for</code>) equals to the value of
 * <code>protocolHeaderHttpsValue</code> configuration parameter (default <code>https</code>) then <code>request.isSecure = true</code>,
 * <code>request.scheme = https</code> and <code>request.serverPort = 443</code>. Note that 443 can be overwritten with the
 * <code>$httpsServerPort</code> configuration parameter.</li>
 * </ul>
 * </p>
 * <p>
 * <strong>Configuration parameters:</strong>
 * <table border="1">
 * <tr>
 * <th>RemoteIpValve property</th>
 * <th>Description</th>
 * <th>Equivalent mod_remoteip directive</th>
 * <th>Format</th>
 * <th>Default Value</th>
 * </tr>
 * <tr>
 * <td>remoteIpHeader</td>
 * <td>Name of the Http Header read by this valve that holds the list of traversed IP addresses starting from the requesting client</td>
 * <td>RemoteIPHeader</td>
 * <td>Compliant http header name</td>
 * <td>x-forwarded-for</td>
 * </tr>
 * <tr>
 * <td>internalProxies</td>
 * <td>List of internal proxies ip adress. If they appear in the <code>remoteIpHeader</code> value, they will be trusted and will not appear
 * in the <code>proxiesHeader</code> value</td>
 * <td>RemoteIPInternalProxy</td>
 * <td>Comma delimited list of regular expressions (in the syntax supported by the {@link java.util.regex.Pattern} library)</td>
 * <td>10\.\d{1,3}\.\d{1,3}\.\d{1,3}, 192\.168\.\d{1,3}\.\d{1,3}, 169\.254\.\d{1,3}\.\d{1,3}, 127\.\d{1,3}\.\d{1,3}\.\d{1,3} <br/>
 * Note that this comma-separated regular expression <i>is</i> used by default
 * but cannot be specified in the same way through String-based configuration,
 * as the commas in the "\d{1,3}" expressions will interpreted as separators
 * between regular expressions. The "\d{1,3}" pattern can be replaced by
 * "\d\d?\d?" or more simply by "\d+".
 * By default, 10/8, 192.168/16, 169.254/16 and 127/8 are allowed ; 172.16/12 has not been enabled by default because it is complex to
 * describe with regular expressions</td>
 * </tr>
 * </tr>
 * <tr>
 * <td>proxiesHeader</td>
 * <td>Name of the http header created by this valve to hold the list of proxies that have been processed in the incoming
 * <code>remoteIpHeader</code></td>
 * <td>RemoteIPProxiesHeader</td>
 * <td>Compliant http header name</td>
 * <td>x-forwarded-by</td>
 * </tr>
 * <tr>
 * <td>trustedProxies</td>
 * <td>List of trusted proxies ip adress. If they appear in the <code>remoteIpHeader</code> value, they will be trusted and will appear
 * in the <code>proxiesHeader</code> value</td>
 * <td>RemoteIPTrustedProxy</td>
 * <td>Comma delimited list of regular expressions (in the syntax supported by the {@link java.util.regex.Pattern} library)</td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>protocolHeader</td>
 * <td>Name of the http header read by this valve that holds the flag that this request </td>
 * <td>N/A</td>
 * <td>Compliant http header name like <code>X-Forwarded-Proto</code>, <code>X-Forwarded-Ssl</code> or <code>Front-End-Https</code></td>
 * <td><code>null</code></td>
 * </tr>
 * <tr>
 * <td>protocolHeaderHttpsValue</td>
 * <td>Value of the <code>protocolHeader</code> to indicate that it is an Https request</td>
 * <td>N/A</td>
 * <td>String like <code>https</code> or <code>ON</code></td>
 * <td><code>https</code></td>
 * </tr>
 * <tr>
 * <td>httpServerPort</td>
 * <td>Value returned by {@link ServletRequest#getServerPort()} when the <code>protocolHeader</code> indicates <code>http</code> protocol</td>
 * <td>N/A</td>
 * <td>integer</td>
 * <td>80</td>
 * </tr>
 * <tr>
 * <td>httpsServerPort</td>
 * <td>Value returned by {@link ServletRequest#getServerPort()} when the <code>protocolHeader</code> indicates <code>https</code> protocol</td>
 * <td>N/A</td>
 * <td>integer</td>
 * <td>443</td>
 * </tr>
 * </table>
 * </p>
 * <p>
 * <p>
 * This Valve may be attached to any Container, depending on the granularity of the filtering you wish to perform.
 * </p>
 * <p>
 * <strong>Regular expression vs. IP address blocks:</strong> <code>mod_remoteip</code> allows to use address blocks (e.g.
 * <code>192.168/16</code>) to configure <code>RemoteIPInternalProxy</code> and <code>RemoteIPTrustedProxy</code> ; as Tomcat doesn't have a
 * library similar to <a
 * href="http://apr.apache.org/docs/apr/1.3/group__apr__network__io.html#gb74d21b8898b7c40bf7fd07ad3eb993d">apr_ipsubnet_test</a>,
 * <code>RemoteIpValve</code> uses regular expression to configure <code>internalProxies</code> and <code>trustedProxies</code> in the same
 * fashion as {@link RequestFilterValve} does.
 * </p>
 * <hr/>
 * <p>
 * <strong>Sample with internal proxies</strong>
 * </p>
 * <p>
 * RemoteIpValve configuration:
 * </p>
 * <code><pre>
 * &lt;Valve
 *   className="org.apache.catalina.valves.RemoteIpValve"
 *   internalProxies="192\.168\.0\.10, 192\.168\.0\.11"
 *   remoteIpHeader="x-forwarded-for"
 *   remoteIpProxiesHeader="x-forwarded-by"
 *   protocolHeader="x-forwarded-proto"
 *   /&gt;</pre></code>
 * <p>
 * Request values:
 * <table border="1">
 * <tr>
 * <th>property</th>
 * <th>Value Before RemoteIpValve</th>
 * <th>Value After RemoteIpValve</th>
 * </tr>
 * <tr>
 * <td>request.remoteAddr</td>
 * <td>192.168.0.10</td>
 * <td>140.211.11.130</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-for']</td>
 * <td>140.211.11.130, 192.168.0.10</td>
 * <td>null</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-by']</td>
 * <td>null</td>
 * <td>null</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-proto']</td>
 * <td>https</td>
 * <td>https</td>
 * </tr>
 * <tr>
 * <td>request.scheme</td>
 * <td>http</td>
 * <td>https</td>
 * </tr>
 * <tr>
 * <td>request.secure</td>
 * <td>false</td>
 * <td>true</td>
 * </tr>
 * <tr>
 * <td>request.serverPort</td>
 * <td>80</td>
 * <td>443</td>
 * </tr>
 * </table>
 * Note : <code>x-forwarded-by</code> header is null because only internal proxies as been traversed by the request.
 * <code>x-forwarded-by</code> is null because all the proxies are trusted or internal.
 * </p>
 * <hr/>
 * <p>
 * <strong>Sample with trusted proxies</strong>
 * </p>
 * <p>
 * RemoteIpValve configuration:
 * </p>
 * <code><pre>
 * &lt;Valve
 *   className="org.apache.catalina.valves.RemoteIpValve"
 *   internalProxies="192\.168\.0\.10, 192\.168\.0\.11"
 *   remoteIpHeader="x-forwarded-for"
 *   remoteIpProxiesHeader="x-forwarded-by"
 *   trustedProxies="proxy1, proxy2"
 *   /&gt;</pre></code>
 * <p>
 * Request values:
 * <table border="1">
 * <tr>
 * <th>property</th>
 * <th>Value Before RemoteIpValve</th>
 * <th>Value After RemoteIpValve</th>
 * </tr>
 * <tr>
 * <td>request.remoteAddr</td>
 * <td>192.168.0.10</td>
 * <td>140.211.11.130</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-for']</td>
 * <td>140.211.11.130, proxy1, proxy2</td>
 * <td>null</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-by']</td>
 * <td>null</td>
 * <td>proxy1, proxy2</td>
 * </tr>
 * </table>
 * Note : <code>proxy1</code> and <code>proxy2</code> are both trusted proxies that come in <code>x-forwarded-for</code> header, they both
 * are migrated in <code>x-forwarded-by</code> header. <code>x-forwarded-by</code> is null because all the proxies are trusted or internal.
 * </p>
 * <hr/>
 * <p>
 * <strong>Sample with internal and trusted proxies</strong>
 * </p>
 * <p>
 * RemoteIpValve configuration:
 * </p>
 * <code><pre>
 * &lt;Valve
 *   className="org.apache.catalina.valves.RemoteIpValve"
 *   internalProxies="192\.168\.0\.10, 192\.168\.0\.11"
 *   remoteIpHeader="x-forwarded-for"
 *   remoteIpProxiesHeader="x-forwarded-by"
 *   trustedProxies="proxy1, proxy2"
 *   /&gt;</pre></code>
 * <p>
 * Request values:
 * <table border="1">
 * <tr>
 * <th>property</th>
 * <th>Value Before RemoteIpValve</th>
 * <th>Value After RemoteIpValve</th>
 * </tr>
 * <tr>
 * <td>request.remoteAddr</td>
 * <td>192.168.0.10</td>
 * <td>140.211.11.130</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-for']</td>
 * <td>140.211.11.130, proxy1, proxy2, 192.168.0.10</td>
 * <td>null</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-by']</td>
 * <td>null</td>
 * <td>proxy1, proxy2</td>
 * </tr>
 * </table>
 * Note : <code>proxy1</code> and <code>proxy2</code> are both trusted proxies that come in <code>x-forwarded-for</code> header, they both
 * are migrated in <code>x-forwarded-by</code> header. As <code>192.168.0.10</code> is an internal proxy, it does not appear in
 * <code>x-forwarded-by</code>. <code>x-forwarded-by</code> is null because all the proxies are trusted or internal.
 * </p>
 * <hr/>
 * <p>
 * <strong>Sample with an untrusted proxy</strong>
 * </p>
 * <p>
 * RemoteIpValve configuration:
 * </p>
 * <code><pre>
 * &lt;Valve
 *   className="org.apache.catalina.valves.RemoteIpValve"
 *   internalProxies="192\.168\.0\.10, 192\.168\.0\.11"
 *   remoteIpHeader="x-forwarded-for"
 *   remoteIpProxiesHeader="x-forwarded-by"
 *   trustedProxies="proxy1, proxy2"
 *   /&gt;</pre></code>
 * <p>
 * Request values:
 * <table border="1">
 * <tr>
 * <th>property</th>
 * <th>Value Before RemoteIpValve</th>
 * <th>Value After RemoteIpValve</th>
 * </tr>
 * <tr>
 * <td>request.remoteAddr</td>
 * <td>192.168.0.10</td>
 * <td>untrusted-proxy</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-for']</td>
 * <td>140.211.11.130, untrusted-proxy, proxy1</td>
 * <td>140.211.11.130</td>
 * </tr>
 * <tr>
 * <td>request.header['x-forwarded-by']</td>
 * <td>null</td>
 * <td>proxy1</td>
 * </tr>
 * </table>
 * Note : <code>x-forwarded-by</code> holds the trusted proxy <code>proxy1</code>. <code>x-forwarded-by</code> holds
 * <code>140.211.11.130</code> because <code>untrusted-proxy</code> is not trusted and thus, we can not trust that
 * <code>untrusted-proxy</code> is the actual remote ip. <code>request.remoteAddr</code> is <code>untrusted-proxy</code> that is an IP
 * verified by <code>proxy1</code>.
 * </p>
 */
public class RemoteIpValve extends ValveBase
{

    /**
     * {@link Pattern} for a comma delimited string that support whitespace characters
     */
    private static final Pattern commaSeparatedValuesPattern = Pattern.compile("\\s*,\\s*");

    /**
     * The descriptive information related to this implementation.
     */
    private static final String info = "org.apache.catalina.valves.RemoteIpValve/1.0";
    /**
     * The StringManager for this package.
     */
    protected static StringManager sm = StringManager.getManager(Constants.Package);
    /**
     * Logger
     */
    private static Log log = LogFactory.getLog(RemoteIpValve.class);
    /**
     * @see #setHttpServerPort(int)
     */
    private int httpServerPort = 80;
    /**
     * @see #setHttpsServerPort(int)
     */
    private int httpsServerPort = 443;
    /**
     * @see #setInternalProxies(String)
     */
    private Pattern[] internalProxies = new Pattern[]{
            Pattern.compile("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"), Pattern.compile("192\\.168\\.\\d{1,3}\\.\\d{1,3}"),
            Pattern.compile("169\\.254\\.\\d{1,3}\\.\\d{1,3}"), Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
    };
    /**
     * @see #setProtocolHeader(String)
     */
    private String protocolHeader = null;
    /**
     * @see #setProtocolHeaderHttpsValue(String)
     */
    private String protocolHeaderHttpsValue = "https";
    /**
     * @see #setProxiesHeader(String)
     */
    private String proxiesHeader = "X-Forwarded-By";
    /**
     * @see #setRemoteIpHeader(String)
     */
    private String remoteIpHeader = "X-Forwarded-For";
    /**
     * @see RemoteIpValve#setTrustedProxies(String)
     */
    private Pattern[] trustedProxies = new Pattern[0];

    /**
     * Convert a given comma delimited list of regular expressions into an array of compiled {@link Pattern}
     *
     * @return array of patterns (not <code>null</code>)
     */
    protected static Pattern[] commaDelimitedListToPatternArray(String commaDelimitedPatterns)
    {
        String[] patterns = commaDelimitedListToStringArray(commaDelimitedPatterns);
        List<Pattern> patternsList = new ArrayList<Pattern>();
        for (String pattern : patterns)
        {
            try
            {
                patternsList.add(Pattern.compile(pattern));
            }
            catch (PatternSyntaxException e)
            {
                throw new IllegalArgumentException(sm.getString("remoteIpValve.syntax", pattern), e);
            }
        }
        return patternsList.toArray(new Pattern[0]);
    }

    /**
     * Convert a given comma delimited list of regular expressions into an array of String
     *
     * @return array of patterns (non <code>null</code>)
     */
    protected static String[] commaDelimitedListToStringArray(String commaDelimitedStrings)
    {
        return (commaDelimitedStrings == null || commaDelimitedStrings.length() == 0) ? new String[0] : commaSeparatedValuesPattern
                .split(commaDelimitedStrings);
    }

    /**
     * Convert an array of strings in a comma delimited string
     */
    protected static String listToCommaDelimitedString(List<String> stringList)
    {
        if (stringList == null)
        {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Iterator<String> it = stringList.iterator(); it.hasNext(); )
        {
            Object element = it.next();
            if (element != null)
            {
                result.append(element);
                if (it.hasNext())
                {
                    result.append(", ");
                }
            }
        }
        return result.toString();
    }

    /**
     * Return <code>true</code> if the given <code>str</code> matches at least one of the given <code>patterns</code>.
     */
    protected static boolean matchesOne(String str, Pattern... patterns)
    {
        for (Pattern pattern : patterns)
        {
            if (pattern.matcher(str).matches())
            {
                return true;
            }
        }
        return false;
    }

    public int getHttpsServerPort()
    {
        return httpsServerPort;
    }

    /**
     * <p>
     * Server Port value if the {@link #protocolHeader} indicates HTTPS
     * </p>
     * <p>
     * Default value : 443
     * </p>
     */
    public void setHttpsServerPort(int httpsServerPort)
    {
        this.httpsServerPort = httpsServerPort;
    }

    public int getHttpServerPort()
    {
        return httpServerPort;
    }

    /**
     * <p>
     * Server Port value if the {@link #protocolHeader} is not <code>null</code> and does not indicate HTTP
     * </p>
     * <p>
     * Default value : 80
     * </p>
     */
    public void setHttpServerPort(int httpServerPort)
    {
        this.httpServerPort = httpServerPort;
    }

    /**
     * Return descriptive information about this Valve implementation.
     */
    @Override
    public String getInfo()
    {
        return info;
    }

    /**
     * @return comma delimited list of internal proxies
     * @see #setInternalProxies(String)
     */
    public String getInternalProxies()
    {
        List<String> internalProxiesAsStringList = new ArrayList<String>();
        for (Pattern internalProxyPattern : internalProxies)
        {
            internalProxiesAsStringList.add(String.valueOf(internalProxyPattern));
        }
        return listToCommaDelimitedString(internalProxiesAsStringList);
    }

    /**
     * <p>
     * Comma delimited list of internal proxies. Can be expressed with regular expressions.
     * </p>
     * <p>
     * Default value: 10\.\d{1,3}\.\d{1,3}\.\d{1,3}, 192\.168\.\d{1,3}\.\d{1,3}, 127\.\d{1,3}\.\d{1,3}\.\d{1,3}<br/>
     * Note: If you want to configure the same value, you have to replace
     * "\d{1,3}" with "\d\d?\d?" or more simply with "\d+". Otherwise the commas
     * in the expression will be mistaken for separators between regular expressions.
     * </p>
     */
    public void setInternalProxies(String commaDelimitedInternalProxies)
    {
        this.internalProxies = commaDelimitedListToPatternArray(commaDelimitedInternalProxies);
    }

    /**
     * @return the protocol header (e.g. "X-Forwarded-Proto")
     * @see #setProtocolHeader(String)
     */
    public String getProtocolHeader()
    {
        return protocolHeader;
    }

    /**
     * <p>
     * Header that holds the incoming protocol, usally named <code>X-Forwarded-Proto</code>. If <code>null</code>, request.scheme and
     * request.secure will not be modified.
     * </p>
     * <p>
     * Default value : <code>null</code>
     * </p>
     */
    public void setProtocolHeader(String protocolHeader)
    {
        this.protocolHeader = protocolHeader;
    }

    /**
     * @return the value of the protocol header for incoming https request (e.g. "https")
     * @see RemoteIpValve#setProtocolHeaderHttpsValue(String)
     */
    public String getProtocolHeaderHttpsValue()
    {
        return protocolHeaderHttpsValue;
    }

    /**
     * <p>
     * Case insensitive value of the protocol header to indicate that the incoming http request uses SSL.
     * </p>
     * <p>
     * Default value : <code>https</code>
     * </p>
     */
    public void setProtocolHeaderHttpsValue(String protocolHeaderHttpsValue)
    {
        this.protocolHeaderHttpsValue = protocolHeaderHttpsValue;
    }

    /**
     * @return the proxies header name (e.g. "X-Forwarded-By")
     * @see #setProxiesHeader(String)
     */
    public String getProxiesHeader()
    {
        return proxiesHeader;
    }

    /**
     * <p>
     * The proxiesHeader directive specifies a header into which mod_remoteip will collect a list of all of the intermediate client IP
     * addresses trusted to resolve the actual remote IP. Note that intermediate RemoteIPTrustedProxy addresses are recorded in this header,
     * while any intermediate RemoteIPInternalProxy addresses are discarded.
     * </p>
     * <p>
     * Name of the http header that holds the list of trusted proxies that has been traversed by the http request.
     * </p>
     * <p>
     * The value of this header can be comma delimited.
     * </p>
     * <p>
     * Default value : <code>X-Forwarded-By</code>
     * </p>
     */
    public void setProxiesHeader(String proxiesHeader)
    {
        this.proxiesHeader = proxiesHeader;
    }

    /**
     * @return the remote IP header name (e.g. "X-Forwarded-For")
     * @see #setRemoteIpHeader(String)
     */
    public String getRemoteIpHeader()
    {
        return remoteIpHeader;
    }

    /**
     * <p>
     * Name of the http header from which the remote ip is extracted.
     * </p>
     * <p>
     * The value of this header can be comma delimited.
     * </p>
     * <p>
     * Default value : <code>X-Forwarded-For</code>
     * </p>
     *
     * @param remoteIpHeader
     */
    public void setRemoteIpHeader(String remoteIpHeader)
    {
        this.remoteIpHeader = remoteIpHeader;
    }

    /**
     * @return comma delimited list of trusted proxies
     * @see #setTrustedProxies(String)
     */
    public String getTrustedProxies()
    {
        List<String> trustedProxiesAsStringList = new ArrayList<String>();
        for (Pattern trustedProxy : trustedProxies)
        {
            trustedProxiesAsStringList.add(String.valueOf(trustedProxy));
        }
        return listToCommaDelimitedString(trustedProxiesAsStringList);
    }

    /**
     * <p>
     * Comma delimited list of proxies that are trusted when they appear in the {@link #remoteIpHeader} header. Can be expressed as a
     * regular expression.
     * </p>
     * <p>
     * Default value : empty list, no external proxy is trusted.
     * </p>
     */
    public void setTrustedProxies(String commaDelimitedTrustedProxies)
    {
        this.trustedProxies = commaDelimitedListToPatternArray(commaDelimitedTrustedProxies);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException
    {
        final String originalRemoteAddr = request.getRemoteAddr();
        final String originalRemoteHost = request.getRemoteHost();
        final String originalScheme = request.getScheme();
        final boolean originalSecure = request.isSecure();
        final int originalServerPort = request.getServerPort();

        if (matchesOne(originalRemoteAddr, internalProxies))
        {
            String remoteIp = null;
            // In java 6, proxiesHeaderValue should be declared as a java.util.Deque
            LinkedList<String> proxiesHeaderValue = new LinkedList<String>();
            StringBuffer concatRemoteIpHeaderValue = new StringBuffer();

            for (Enumeration<String> e = request.getHeaders(remoteIpHeader); e.hasMoreElements(); )
            {
                if (concatRemoteIpHeaderValue.length() > 0)
                {
                    concatRemoteIpHeaderValue.append(", ");
                }

                concatRemoteIpHeaderValue.append(e.nextElement());
            }

            String[] remoteIpHeaderValue = commaDelimitedListToStringArray(concatRemoteIpHeaderValue.toString());
            int idx;
            // loop on remoteIpHeaderValue to find the first trusted remote ip and to build the proxies chain
            for (idx = remoteIpHeaderValue.length - 1; idx >= 0; idx--)
            {
                String currentRemoteIp = remoteIpHeaderValue[idx];
                remoteIp = currentRemoteIp;
                if (matchesOne(currentRemoteIp, internalProxies))
                {
                    // do nothing, internalProxies IPs are not appended to the
                } else if (matchesOne(currentRemoteIp, trustedProxies))
                {
                    proxiesHeaderValue.addFirst(currentRemoteIp);
                } else
                {
                    idx--; // decrement idx because break statement doesn't do it
                    break;
                }
            }
            // continue to loop on remoteIpHeaderValue to build the new value of the remoteIpHeader
            LinkedList<String> newRemoteIpHeaderValue = new LinkedList<String>();
            for (; idx >= 0; idx--)
            {
                String currentRemoteIp = remoteIpHeaderValue[idx];
                newRemoteIpHeaderValue.addFirst(currentRemoteIp);
            }
            if (remoteIp != null)
            {

                request.setRemoteAddr(remoteIp);
                request.setRemoteHost(remoteIp);

                // use request.coyoteRequest.mimeHeaders.setValue(str).setString(str) because request.addHeader(str, str) is no-op in Tomcat
                // 6.0
                if (proxiesHeaderValue.size() == 0)
                {
                    request.getCoyoteRequest().getMimeHeaders().removeHeader(proxiesHeader);
                } else
                {
                    String commaDelimitedListOfProxies = listToCommaDelimitedString(proxiesHeaderValue);
                    request.getCoyoteRequest().getMimeHeaders().setValue(proxiesHeader).setString(commaDelimitedListOfProxies);
                }
                if (newRemoteIpHeaderValue.size() == 0)
                {
                    request.getCoyoteRequest().getMimeHeaders().removeHeader(remoteIpHeader);
                } else
                {
                    String commaDelimitedRemoteIpHeaderValue = listToCommaDelimitedString(newRemoteIpHeaderValue);
                    request.getCoyoteRequest().getMimeHeaders().setValue(remoteIpHeader).setString(commaDelimitedRemoteIpHeaderValue);
                }
            }

            if (protocolHeader != null)
            {
                String protocolHeaderValue = request.getHeader(protocolHeader);
                if (protocolHeaderValue == null)
                {
                    // don't modify the secure,scheme and serverPort attributes
                    // of the request
                } else if (protocolHeaderHttpsValue.equalsIgnoreCase(protocolHeaderValue))
                {
                    request.setSecure(true);
                    // use request.coyoteRequest.scheme instead of request.setScheme() because request.setScheme() is no-op in Tomcat 6.0
                    request.getCoyoteRequest().scheme().setString("https");

                    request.setServerPort(httpsServerPort);
                } else
                {
                    request.setSecure(false);
                    // use request.coyoteRequest.scheme instead of request.setScheme() because request.setScheme() is no-op in Tomcat 6.0
                    request.getCoyoteRequest().scheme().setString("http");

                    request.setServerPort(httpServerPort);
                }
            }

            if (log.isDebugEnabled())
            {
                log.debug("Incoming request " + request.getRequestURI() + " with originalRemoteAddr '" + originalRemoteAddr
                        + "', originalRemoteHost='" + originalRemoteHost + "', originalSecure='" + originalSecure + "', originalScheme='"
                        + originalScheme + "' will be seen as newRemoteAddr='" + request.getRemoteAddr() + "', newRemoteHost='"
                        + request.getRemoteHost() + "', newScheme='" + request.getScheme() + "', newSecure='" + request.isSecure() + "'");
            }
        } else
        {
            if (log.isDebugEnabled())
            {
                log.debug("Skip RemoteIpValve for request " + request.getRequestURI() + " with originalRemoteAddr '"
                        + request.getRemoteAddr() + "'");
            }
        }
        try
        {
            getNext().invoke(request, response);
        }
        finally
        {
            request.setRemoteAddr(originalRemoteAddr);
            request.setRemoteHost(originalRemoteHost);

            request.setSecure(originalSecure);

            // use request.coyoteRequest.scheme instead of request.setScheme() because request.setScheme() is no-op in Tomcat 6.0
            request.getCoyoteRequest().scheme().setString(originalScheme);

            request.setServerPort(originalServerPort);
        }
    }
}
