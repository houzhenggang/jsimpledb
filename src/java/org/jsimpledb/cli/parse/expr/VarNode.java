
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb.cli.parse.expr;

import org.jsimpledb.cli.Session;

/**
 * Runtime value representing a session variable.
 */
public class VarNode extends AbstractNamed implements Node {

    /**
     * Constructor.
     *
     * @param name variable name
     * @throws IllegalArgumentException if name is null
     * @throws IllegalArgumentException if name is not a valid Java identifier
     */
    public VarNode(String name) {
        super(name);
    }

// Node

    @Override
    public Value evaluate(Session session) {
        return new DynamicValue() {

            @Override
            public Object get(Session session) {
                final Object value = session.getVars().get(VarNode.this.name);
                if (value == null && !session.getVars().containsKey(VarNode.this.name))
                    throw new EvalException("variable `" + name + "' is not defined");
                return value;
            }

            @Override
            public void set(Session session, Object value) {
                session.getVars().put(VarNode.this.name, value);
            }
        };
    }
}

