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


package org.apache.catalina.startup;


import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.digester.RuleSetBase;


/**
 * <p><strong>RuleSet</strong> for processing the contents of a Realm definition
 * element.  This <code>RuleSet</code> supports Realms such as the
 * <code>CombinedRealm</code> that used nested Realms.</p>
 */

public class RealmRuleSet extends RuleSetBase
{


    // ----------------------------------------------------- Instance Variables


    /**
     * The matching pattern prefix to use for recognizing our elements.
     */
    protected String prefix = null;


    // ------------------------------------------------------------ Constructor


    /**
     * Construct an instance of this <code>RuleSet</code> with the default
     * matching pattern prefix.
     */
    public RealmRuleSet()
    {

        this("");

    }


    /**
     * Construct an instance of this <code>RuleSet</code> with the specified
     * matching pattern prefix.
     *
     * @param prefix Prefix for matching pattern rules (including the
     *               trailing slash character)
     */
    public RealmRuleSet(String prefix)
    {

        super();
        this.namespaceURI = null;
        this.prefix = prefix;

    }


    // --------------------------------------------------------- Public Methods


    /**
     * <p>Add the set of Rule instances defined in this RuleSet to the
     * specified <code>Digester</code> instance, associating them with
     * our namespace URI (if any).  This method should only be called
     * by a Digester instance.</p>
     *
     * @param digester Digester instance to which the new Rule instances
     *                 should be added.
     */
    public void addRuleInstances(Digester digester)
    {

        digester.addObjectCreate(prefix + "Realm",
                null, // MUST be specified in the element,
                "className");
        digester.addSetProperties(prefix + "Realm");
        digester.addSetNext(prefix + "Realm",
                "setRealm",
                "org.apache.catalina.Realm");

        digester.addObjectCreate(prefix + "Realm/Realm",
                null, // MUST be specified in the element
                "className");
        digester.addSetProperties(prefix + "Realm/Realm");
        digester.addSetNext(prefix + "Realm/Realm",
                "addRealm",
                "org.apache.catalina.Realm");

    }


}
