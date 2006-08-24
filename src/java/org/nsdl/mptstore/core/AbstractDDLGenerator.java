package org.nsdl.mptstore.core;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractDDLGenerator implements DDLGenerator {

    // Implements DDLGenerator.getDropMapTableDDL(String)
    public List<String> getDropMapTableDDL(String table) {
        List<String> cmds = new ArrayList<String>();
        cmds.add("DROP TABLE " + table);
        return cmds;
    }

    // Implements DDLGenerator.getDropSOTableDDL(String)
    public List<String> getDropSOTableDDL(String table) {
        List<String> cmds = new ArrayList<String>();
        cmds.add("DROP TABLE " + table);
        return cmds;
    }

}