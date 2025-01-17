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

package org.apache.el.lang;

import org.apache.el.MethodExpressionImpl;
import org.apache.el.MethodExpressionLiteral;
import org.apache.el.ValueExpressionImpl;
import org.apache.el.parser.*;
import org.apache.el.util.ConcurrentCache;
import org.apache.el.util.MessageFactory;

import javax.el.*;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * @author Jacob Hookom [jacob@hookom.net]
 */
public final class ExpressionBuilder implements NodeVisitor
{

    private static final int CACHE_SIZE;
    private static final String CACHE_SIZE_PROP =
            "org.apache.el.ExpressionBuilder.CACHE_SIZE";
    private static final ConcurrentCache<String, Node> cache =
            new ConcurrentCache<String, Node>(CACHE_SIZE);

    static
    {
        if (System.getSecurityManager() == null)
        {
            CACHE_SIZE = Integer.parseInt(
                    System.getProperty(CACHE_SIZE_PROP, "5000"));
        } else
        {
            CACHE_SIZE = AccessController.doPrivileged(
                    new PrivilegedAction<Integer>()
                    {
                        public Integer run()
                        {
                            return Integer.valueOf(
                                    System.getProperty(CACHE_SIZE_PROP, "5000"));
                        }
                    }).intValue();
        }
    }

    private FunctionMapper fnMapper;

    private VariableMapper varMapper;

    private String expression;

    /**
     *
     */
    public ExpressionBuilder(String expression, ELContext ctx)
            throws ELException
    {
        this.expression = expression;

        FunctionMapper ctxFn = ctx.getFunctionMapper();
        VariableMapper ctxVar = ctx.getVariableMapper();

        if (ctxFn != null)
        {
            this.fnMapper = new FunctionMapperFactory(ctxFn);
        }
        if (ctxVar != null)
        {
            this.varMapper = new VariableMapperFactory(ctxVar);
        }
    }

    public final static Node createNode(String expr) throws ELException
    {
        Node n = createNodeInternal(expr);
        return n;
    }

    private final static Node createNodeInternal(String expr)
            throws ELException
    {
        if (expr == null)
        {
            throw new ELException(MessageFactory.get("error.null"));
        }

        Node n = cache.get(expr);
        if (n == null)
        {
            try
            {
                n = (new ELParser(new StringReader(expr)))
                        .CompositeExpression();

                // validate composite expression
                if (n instanceof AstCompositeExpression)
                {
                    int numChildren = n.jjtGetNumChildren();
                    if (numChildren == 1)
                    {
                        n = n.jjtGetChild(0);
                    } else
                    {
                        Class type = null;
                        Node child = null;
                        for (int i = 0; i < numChildren; i++)
                        {
                            child = n.jjtGetChild(i);
                            if (child instanceof AstLiteralExpression)
                                continue;
                            if (type == null)
                                type = child.getClass();
                            else
                            {
                                if (!type.equals(child.getClass()))
                                {
                                    throw new ELException(MessageFactory.get(
                                            "error.mixed", expr));
                                }
                            }
                        }
                    }
                }
                if (n instanceof AstDeferredExpression
                        || n instanceof AstDynamicExpression)
                {
                    n = n.jjtGetChild(0);
                }
                cache.put(expr, n);
            }
            catch (Exception e)
            {
                throw new ELException(
                        MessageFactory.get("error.parseFail", expr), e);
            }
        }
        return n;
    }

    private void prepare(Node node) throws ELException
    {
        try
        {
            node.accept(this);
        }
        catch (Exception e)
        {
            if (e instanceof ELException)
            {
                throw (ELException) e;
            } else
            {
                throw (new ELException(e));
            }
        }
        if (this.fnMapper instanceof FunctionMapperFactory)
        {
            this.fnMapper = ((FunctionMapperFactory) this.fnMapper).create();
        }
        if (this.varMapper instanceof VariableMapperFactory)
        {
            this.varMapper = ((VariableMapperFactory) this.varMapper).create();
        }
    }

    private Node build() throws ELException
    {
        Node n = createNodeInternal(this.expression);
        this.prepare(n);
        if (n instanceof AstDeferredExpression
                || n instanceof AstDynamicExpression)
        {
            n = n.jjtGetChild(0);
        }
        return n;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.sun.el.parser.NodeVisitor#visit(com.sun.el.parser.Node)
     */
    public void visit(Node node) throws ELException
    {
        if (node instanceof AstFunction)
        {

            AstFunction funcNode = (AstFunction) node;

            if (this.fnMapper == null)
            {
                throw new ELException(MessageFactory.get("error.fnMapper.null"));
            }
            Method m = fnMapper.resolveFunction(funcNode.getPrefix(), funcNode
                    .getLocalName());
            if (m == null)
            {
                throw new ELException(MessageFactory.get(
                        "error.fnMapper.method", funcNode.getOutputName()));
            }
            int pcnt = m.getParameterTypes().length;
            if (node.jjtGetNumChildren() != pcnt)
            {
                throw new ELException(MessageFactory.get(
                        "error.fnMapper.paramcount", funcNode.getOutputName(),
                        "" + pcnt, "" + node.jjtGetNumChildren()));
            }
        } else if (node instanceof AstIdentifier && this.varMapper != null)
        {
            String variable = ((AstIdentifier) node).getImage();

            // simply capture it
            this.varMapper.resolveVariable(variable);
        }
    }

    public ValueExpression createValueExpression(Class expectedType)
            throws ELException
    {
        Node n = this.build();
        return new ValueExpressionImpl(this.expression, n, this.fnMapper,
                this.varMapper, expectedType);
    }

    public MethodExpression createMethodExpression(Class expectedReturnType,
                                                   Class[] expectedParamTypes) throws ELException
    {
        Node n = this.build();
        if (n instanceof AstValue || n instanceof AstIdentifier)
        {
            return new MethodExpressionImpl(expression, n, this.fnMapper,
                    this.varMapper, expectedReturnType, expectedParamTypes);
        } else if (n instanceof AstLiteralExpression)
        {
            return new MethodExpressionLiteral(expression, expectedReturnType,
                    expectedParamTypes);
        } else
        {
            throw new ELException("Not a Valid Method Expression: "
                    + expression);
        }
    }
}
