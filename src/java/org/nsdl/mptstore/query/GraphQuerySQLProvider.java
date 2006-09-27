package org.nsdl.mptstore.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.nsdl.mptstore.core.TableManager;
import org.nsdl.mptstore.rdf.PredicateNode;

/** Translates a {@link GraphQuery} into a series of SQL statements
 * <p>
 * Produces ANSI SQL-92 queries by converting each {@link GraphPattern} leaf
 * of the query tree into a series of JOINs.   Each join condition is formed by 
 * matching variables between {@link TriplePattern}s in the appripriate 
 * GraphPatterns.
 * </p>
 * <p>
 * TODO:
 * <ul>
 *  <li> Handle nested subqueries (currently is set to barf if a required or
 * optional compomnent is not a GraphPattern).  Things are set up for doing
 * so, just haven't had the time to do it</li>
 *  <li> Deal with data typing when applying filters </li>
 *  <li> Implement a strategy for dealing with unbound predicates </li>
 *  <li> Testing, especially with escaped/encoded characters and 
 *  manlformed queries </li> 
 *  <li> Clean this up a bit and simplify, if possible </li>
 *  <li> Move the many inner classes to the outside </li>
 * </ul>
 * </p>
 * @author birkland
 *
 */
public class GraphQuerySQLProvider implements SQLBuilder, SQLProvider {
    
    private static final Logger _LOG = Logger.getLogger(GraphQuerySQLProvider.class.getName());
    
    private final GraphQuery query;
    
    private final MappingManager manager;
	private List<String> targets;
    
    private String ordering;
    private String orderingDirection;
    
    private HashMap<String, Set<String>> valueBindings    = new HashMap<String, Set<String>>();
     

    public GraphQuerySQLProvider(TableManager adaptor, GraphQuery query) {
        
        this.manager = new MappingManager(adaptor);
        this.query = query;
    }
    
    
    /** Choose the variables that define result tuples
     * <p>
     * The given list of variables are used for determining
     * which bound values are included in result tuples, and in 
     * what order.  If a variable is specified as a target, it
     * <em>must</em> be present somewhere in the query.  Any 
     * unmatched target will result in error
     * </p>
     * 
     * @param targets List containing names of query variables
     */
    public void setTargets(List<String> targets) {
        this.targets = new ArrayList<String>(targets);
        
        this.ordering = null;
    }

    /** Force an order on the results
     * <p>
     * Given the name of a target variable, results will be ordered by its
     * bound value.  Results may be specified to return in ascending or
     * descending order
     * </p>
     * 
     * @param target Name of the target variable whose value will be the sort key
     * @param desc True if results are to be in desceiding order, false otherwise
     */
    public void orderBy(String target, boolean desc) {
        if (this.targets == null || !this.targets.contains(target)) {
            throw new IllegalArgumentException ("Cannot group by variable '" + 
                    target + "' since it is not in the target list " + targets);
        }
        
        this.ordering = target;
        if (desc) {
            this.orderingDirection = "DESC";
        } else {
            this.orderingDirection = "ASC";
        }
    }
    
    /** Returns a query in ANSI SQL
     * <p>
     * Translates the GraphQuery defined in the constructor, along with
     * any specified orderings , into a set of SQL statements.  The 
     * union of all SQL statement results, executed in order, will
     * represent the entire result set. 
     * </p>
     * 
     * @return list of SQL statements
     * @throws QueryException if there is some error translating the query to SQL
     */
    public List<String> getSQL() throws QueryException {
        
        HashMap<String, String> requiredBindings = new HashMap<String,String>();
        HashMap<String, String> allBindings = new HashMap<String, String>();
        JoinSequence joinSeq = null;
        
        /* Process required elements first */
		for (QueryElement e : query.getRequired()) {
            
            /* Disallow subqueries for the time being */
            if (e.getType().equals(QueryElement.Type.GraphQuery)) {
                throw new QueryException("Currently, we do not support subqueries");
            } else if (!e.getType().equals(QueryElement.Type.GraphPattern)) {
                /* Currently, this will never happen */
                throw new QueryException("Unknown query element type " + e.getType());
            }
            
            if (joinSeq == null) {
                joinSeq = new JoinSequence(parseGraphPattern((GraphPattern) e, requiredBindings));
            } else {
                joinSeq.addJoin(JoinType.innerJoin, parseGraphPattern((GraphPattern) e,requiredBindings), requiredBindings);
            }
        }
        
		allBindings.putAll(requiredBindings);
        
        for (QueryElement e : query.getOptional()) {
            
            HashMap<String, String> optionalBindings = new HashMap<String, String>(requiredBindings);
            
            /* Disallow subqueries for the time being */
            if (e.getType().equals(QueryElement.Type.GraphQuery)) {
                throw new QueryException("Currently, we do not support subqueries");
            } else if (!e.getType().equals(QueryElement.Type.GraphPattern)) {
                /* Currently, this will never happen */
                throw new QueryException("Unknown query element type " + e.getType());
            }
            
            joinSeq.addJoin(JoinType.leftOuterJoin, 
                    parseGraphPattern((GraphPattern) e, optionalBindings), requiredBindings);
            
            addNewMappings(optionalBindings, allBindings);
            
        }
	    
        StringBuilder sql = new StringBuilder();
        
        sql.append("SELECT " + generateTargets(allBindings) + " FROM " + joinSeq);
        
        /* 
         * If there are any values or constraints that remain to be added to the query,
         * add them in a WHERE clause.  NB: They better not be from an optional clause:
         * It would probably be wise to either check here, or prove that an exception would
         * have been thrown already
         */
        if (valueBindings.size() > 0) {
            sql.append(" WHERE ");
            ArrayList<String> valueKeys = new ArrayList<String>(valueBindings.keySet());
            for (int i = 0; i < valueKeys.size(); i++) {
                ArrayList<String> values = new ArrayList<String>(valueBindings.get(valueKeys.get(i)));
                for (int j = 0; j < values.size(); j++) {
                    if (i > 0 || j > 0) {
                        sql.append(" AND ");
                    }
                    sql.append(values.get(j));
                }
            }
        }
        
        if (ordering != null) {
            sql.append(" ORDER BY " + allBindings.get(ordering) + " " + orderingDirection);
            
        }
        
        ArrayList<String> sqlList = new ArrayList<String>();
        sqlList.add(sql.toString());
        return sqlList;
    }

    /** Return the list of variables that represent this query's targets
     * 
     */
    public List<String> getTargets() {
        return new ArrayList<String>(targets);
    }
    
    private Joinable parseGraphPattern(GraphPattern g, HashMap<String, String> variableBindings) throws QueryException {
        
        /* First, organize the filters by variable so that we can map them */
        HashMap<String, Set<MappableNodeFilter>> filters = new HashMap<String, Set<MappableNodeFilter>>();
        
        Set<MappableNodeFilter>givenFilters = new HashSet<MappableNodeFilter>();
        for (NodeFilter filter : g.getFilters()) {
            givenFilters.add(new MappableNodeFilter(filter));
        }
        
        for (MappableNodeFilter f : givenFilters) {
            if (f.getNode().isVariable()) {
                if (!filters.containsKey(f.getNode().getVarName())) {
                    _LOG.debug("Adding " + f.getNode().getVarName() + " To filter pool..\n");
                    filters.put(f.getNode().getVarName(), new HashSet<MappableNodeFilter>());
                }
                filters.get(f.getNode().getVarName()).add(f);
                _LOG.debug("Filter pool contains " + filters.get(f.getNode().getVarName()) + "\n");
            } 
            if (f.getConstraint().isVariable()) {
               if (!filters.containsKey(f.getConstraint().getVarName())) {
                   
                    filters.put(f.getConstraint().getVarName(), new HashSet<MappableNodeFilter>());
                }
               filters.get(f.getConstraint().getVarName()).add(f);
            } 
            
            if (!(f.getNode().isVariable() || f.getConstraint().isVariable())) {
                throw new IllegalArgumentException("Triple filters must contain a variable.  Neither " + 
                        f.getNode().getVarName() + " nor " + f.getConstraint().getVarName() + " is a variable!");
            }
        }
        
        
        /* Next, process each triple pattern in this graph pattern */
        LinkedList<MappableTriplePattern> steps = new LinkedList<MappableTriplePattern>();
        for (TriplePattern p : g.getTriplePatterns()) {
            steps.add(new MappableTriplePattern(p));
        }

        MappableTriplePattern step = steps.removeFirst();
        
        bindPattern(step, variableBindings);
        JoinSequence joins = new JoinSequence(new JoinTable(step));
        
        Set<MappableNodePattern>joinableVars = joins.joinVars();
        
        while (!steps.isEmpty()) {
            step = getJoinablePattern(steps, variableBindings);
            if (step == null) {
                throw new QueryException("Cannot bind all query steps! \n remaining:\n" + steps + 
                        "\nvariables already bound:\n " + variableBindings.keySet() + "\n");
            }
            steps.remove(step);
            
            bindPattern(step, variableBindings);
            JoinTable table = new JoinTable(step);
            
            joinableVars.addAll(table.joinVars());
            JoinConditions conditions = new JoinConditions();
            
            
            for (MappableNodePattern p : step.getNodes()) {
                if (isBound(p, variableBindings)) {
                    /* Join this variable's column with the corresponding bound column */
                    /* Note: may result in adding redundant join conditions, but that's not 
                     * a real problem */
                    if (!p.mappedName().equals(getBoundValue(p, variableBindings))) {
                        _LOG.debug("parseGraphPattern: Adding Join Condition " + 
                                p.mappedName() +  " = " +  getBoundValue(p, variableBindings) + "'"+ "\n");
                        conditions.addCondition(p.mappedName(), " = ", "'" + getBoundValue(p, variableBindings) + "'");
                    }
                }
            } 
            
            /* Add any filter constraints */
            for (String filterVar : filters.keySet()) {
                for (MappableNodePattern joinableVar : joinableVars) {
                    if (joinableVar.isVariable() && joinableVar.getVarName().equals(filterVar)) {
                        _LOG.debug("match!\n");
                        for (MappableNodeFilter f : filters.get(filterVar)) {
                            String right;
                            String left;
                            
                            if (f.getNode().isVariable() && f.getNode().getVarName().equals(filterVar)) {
                                left = getBoundValue(joinableVar, variableBindings);
                            } else if (f.getNode().isVariable()) {
                                left = getBoundValue(f.getNode(), variableBindings);
                            } else {
                                left = "'" + f.getNode().getNode() + "'";
                            }
                            
                            if (f.getConstraint().isVariable() && f.getConstraint().getVarName().equals(filterVar)) {
                                right = getBoundValue(joinableVar, variableBindings);
                            } else if (f.getConstraint().isVariable()) {
                                right = getBoundValue(f.getConstraint(), variableBindings);
                            } else {
                                right = "'" + f.getConstraint().getNode()+ "'";
                            }
                            
                            conditions.addCondition(left, f.getOperator(), right);
                            _LOG.debug("Adding filter condition: " + left + " " + f.getOperator() + " " + right + "\n");
                        }
                        
                        removeFromMap(filters.get(filterVar), filters);
                    }
                }
            }
            
            /* Fold in any remaining constant bindings */
            for (MappableNodePattern var : joinableVars) {
                if (valueBindings.containsKey(var.boundTable().alias())) {
                    
                    for (String condition : valueBindings.get(var.boundTable().alias())) {
                        _LOG.debug("Adding remaining constant vonditions " + condition + "\n");
                        conditions.addCondition(condition);
                    }
                    valueBindings.remove(var.boundTable().alias());
                }
            }
            
            /* Finally, add the join to the sequence */
            joins.addJoin(JoinType.innerJoin, table, conditions);
        }
        
        /* We weren't able to add filters at this stage.. This is legitimate only if 
         * this pattern has a length of 1 AND it contains a variable that matches the filter
         */
        if (filters.values().size() > 0 && g.getTriplePatterns().size() > 1) {
            throw new QueryException("Filter is unbound");
        }
        
        MappableTriplePattern p = new MappableTriplePattern(g.getTriplePatterns().get(0));

        
        /* Process any remaining filters */
        for (String varName : filters.keySet()) {

            for (MappableNodeFilter f : filters.get(varName)) {
                String mappedName;
                if (p.subject.isVariable() && p.subject.getVarName().equals(varName)) {
                    mappedName = p.subject.mappedName();
                } else if (p.object.isVariable() && p.object.getVarName().equals(varName)) {
                    mappedName = p.object.mappedName();
                } else {
                    throw new QueryException("Variable " + varName + " in filter Cannot be found in graph query");
                }
                
                if (!valueBindings.containsKey(mappedName)) {
                    valueBindings.put(mappedName, new HashSet<String>());
                }
                
                if (f.getNode().isVariable() && f.getNode().getVarName().equals(varName)) {
                    if (f.getConstraint().isVariable()) {
                        /* XXX check this */
                    } else {
                        valueBindings.get(mappedName).add(
                                mappedName + " " + f.getOperator() + " '" + f.getConstraint().getNode() + "'");
                        _LOG.debug("Remaining Filters: " + mappedName + " " + f.getOperator() + " '" + f.getConstraint().getNode() + "'" + "\n");
                    }
                } else if (f.getConstraint().isVariable() && f.getConstraint().getVarName().equals(varName) ){
                    if (f.getNode().isVariable()) {
                        /* XXX: check this */
                    } else {
                        valueBindings.get(mappedName).add(
                                "'" + f.getNode().getNode() + "' " + f.getOperator() + " " + mappedName);
                        _LOG.debug("Remainig Filters: " + "'" + f.getNode().getNode() + "' " + f.getOperator() + " " + mappedName + "\n");
                    }
                } 
            }
        }
        return joins;
    }
    
    private MappableTriplePattern getJoinablePattern(List<MappableTriplePattern> l, HashMap<String, String> variableBinsings) {
        for (MappableTriplePattern p : l) {
            if (isBound(p.subject, variableBinsings) || isBound(p.object, variableBinsings)) {
                return p;
            }
        }
        return null;
    }
    
    /*
     * Determine if a variable has been apped to a literal or 
     * specific column of a table
     */
    private boolean isBound(MappableNodePattern n, HashMap<String, String> variableBindings) {
        if (n.isVariable()) {
            return variableBindings.containsKey(n.getVarName());
        } else {
            return true;
        }
    }
    
    private String getBoundValue(MappableNodePattern n, HashMap<String, String> variableBindings) {
        if (n.isVariable()) {
            return variableBindings.get(n.getVarName());
        } else {
            return n.getNode().toString();
        }
    }
    
    /*
     * Bind the variables/values of a triple pattern by:
     * - Placing any new variables into the master bings map,
     * - Placing any literal values into the literals map
     */
    private void bindPattern(MappableTriplePattern t, HashMap<String, String>variableBindings) {
        t.bindTo(manager.mapPredicateTable(t.predicate));
        
        bindNode(t.subject, variableBindings);
        //bindNode(t.predicate, variableBindings);
        bindNode(t.object, variableBindings);

    }
    
    /* TODO: be able to bind a predicate node */
    private void bindNode(MappableNodePattern p, HashMap<String, String>variableBindings) {
        if (p.isVariable()) {
            if (! variableBindings.containsKey(p.getVarName())) {
                _LOG.debug("Bound " + p.getVarName() + " to " + p.mappedName() + "\n");
                variableBindings.put(p.getVarName(), p.mappedName());
            }
        } else {
            if (!valueBindings.containsKey(p.boundTable().alias())) {
                valueBindings.put(p.boundTable().alias(), new HashSet<String>());
            }
            _LOG.debug("bindNode: adding valueBinding "+ p.mappedName() + " = " + "'" + p.getNode() + "'\n");
            valueBindings.get(p.boundTable().alias()).add(p.mappedName() + " = " + "'" + p.getNode() + "'");
        }
    }
    
    
    /** Removes a mapped value (and all associated keys) from a map
     *
     * @param value mapped value to remove
     * @param m map to remove the value from
     */
    private <K, V> void removeFromMap(V value, Map<K,V> m) {
        for (Map.Entry<K, V> e : m.entrySet()) {
            if (e.getValue().equals(value)) {
                m.remove(e.getKey());
            }
        }
    }
    
    private String generateTargets(HashMap<String, String> variableBindings) {
        String selects = "";
        for (int i = 0; i < targets.size(); i++) {
            selects += variableBindings.get(targets.get(i));
            if (i < targets.size() - 1) {selects += ", ";}
        }
        return selects;
    }
    
    private <K, V> void addNewMappings(Map<K,V> from, Map<K,V> to) {
        for (K key : from.keySet()) {
            if (!to.containsKey(key)) {
                to.put(key, from.get(key));
            }
        }
    }
    
    private interface Joinable {
        public Set<MappableNodePattern> joinVars();
        public String alias();
        public String declaration();
    }
    
    private class JoinTable implements Joinable{
        private final MappableTriplePattern t;
        public JoinTable(MappableTriplePattern t) {
            this.t = t;
        }
        
        public Set<MappableNodePattern> joinVars() {
            HashSet<MappableNodePattern> s = new HashSet<MappableNodePattern>();
            if (t.subject.isVariable()) {
                s.add(t.subject);
            }
            
            if (t.object.isVariable()) {
                s.add(t.object);
            }
            
            return s;
        }
        
        public String alias() {
            return t.subject.boundTable().alias();
        }
        
        public String declaration() {
            String alias = t.subject.boundTable().alias();
            String name = t.subject.boundTable().name();
            if (name.equals(alias)) {
                return name;
            } else {
                return (name + " AS " + alias);
            }
        }
    }
    
    private class JoinSequence implements Joinable {
        private final StringBuilder join;
        private int joinCount = 0;
        
        private final List<Joinable> joined = new ArrayList<Joinable>();
        public JoinSequence(Joinable start) {
            this.join = new StringBuilder(start.declaration());
            joined.add(start);
            joinCount = 1;
        }
        
        public void addJoin(String joinType, Joinable j, String joinConstraints) {
            join.append(" " + joinType + " " + j.declaration());
            joined.add(j);
            if (joinConstraints != null && joinConstraints != "") {
                join.append(" ON (" + joinConstraints + ")");
            }
            joinCount++;
        }
        
        public void addJoin(String joinType, Joinable j, JoinConditions conditions) {
            addJoin(joinType, j, conditions.toString());
        }
        
        public void addJoin(String joinType, Joinable j, HashMap<String, String> variableBindings) {
            
            JoinConditions conditions = new JoinConditions();
            
            
            for (MappableNodePattern existingVar : this.joinVars()) {
                for (MappableNodePattern candidateVar : j.joinVars()) {
                    if (existingVar.getVarName().equals(candidateVar.getVarName()) 
                            && variableBindings.get(candidateVar.getVarName()).equals(existingVar.mappedName())) {
                        conditions.addCondition(existingVar.mappedName(), "=", candidateVar.mappedName());
                    }
                }
            }
            
            addJoin(joinType, j, conditions);
        }
        
        public Set<MappableNodePattern> joinVars() {
            HashSet<MappableNodePattern> joinVars = new HashSet<MappableNodePattern>();
            
            for (Joinable join : joined) {
                joinVars.addAll(join.joinVars());
            }
            
            return joinVars;
        }
        
        public String alias() {
            if (joinCount == 1) return join.toString();
            else return "(" + join.toString() + ")";
        }
        
        public String declaration() {
            return alias();
        }
        
        public String toString() {
            return join.toString();
        }
    }
    
    private class JoinConditions {
        private Set<String> conditions = new HashSet<String>();
        
        public void addCondition(String leftOperand, String operator, String rightOperand ) {
            addCondition(leftOperand.trim() + " " + operator.trim() + " " + rightOperand.trim());
        }
        
        public void addCondition(String condition) {
            conditions.add(condition.trim());
        }
        
        public String toString () {
            StringBuilder joinClause = new StringBuilder();
            for (String condition : conditions) {
                if (joinClause.length() == 0) {
                    joinClause.append(condition);
                } else {
                    joinClause.append(" AND " + condition);
                }
                    
            }
            return joinClause.toString();
        }
    }
    private class JoinType {
        public static final String leftOuterJoin = "LEFT OUTER JOIN";
        public static final String innerJoin = "JOIN";
    }
    
    private class MappingManager {
        private HashMap<String, List<String>> predicateMap 
                = new HashMap<String, List<String>>();
        private TableManager adaptor;
        
        public MappingManager(TableManager adaptor) {
            this.adaptor = adaptor;
        }
        
        public MPTable mapPredicateTable(MappableNodePattern<PredicateNode> predicate) {
            if (predicate.isVariable()) {
                throw new IllegalArgumentException("predicate must not be a variable");
            }
            String tableName = adaptor.getTableFor(predicate.getNode());
            String alias;
            if (predicateMap.containsKey(predicate.getNode().toString())) {
                List<String> aliases = predicateMap.get(predicate.getNode().toString());
                alias = tableName + "_" + aliases.size();
                aliases.add(alias);
            } else {
                ArrayList<String> aliases = new ArrayList<String>();
                aliases.add(tableName);
                predicateMap.put(predicate.getNode().toString(), aliases);
                alias = tableName;
            }
            
            MPTable table = new MPTable (tableName, alias);
            return table;
        }
    }
}
