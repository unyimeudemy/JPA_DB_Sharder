package com.piraxx.sharder.sharderPackage;

import com.piraxx.sharder.domain.TransactionEntity;
import com.piraxx.sharder.sharderPackage.utils.ResourceCloser;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Aspect
@Component
public class ShardingAspect {

    static ConsistentHashing consistentHashing = new ConsistentHashing();

    private static final Logger logger = LoggerFactory.getLogger(ShardingAspect.class);


    @Before("execution(* com.piraxx..repositories..*(..))")
    private void shardingAspect(JoinPoint joinPoint) throws IllegalAccessException, SQLException {
        Boolean usesRawQuery = isAnnotatedWithQuery(joinPoint);
        if(usesRawQuery){
            processRequestWithRawSqlQuery(joinPoint);
        }else {
            processRequestWithoutRawSqlQuery(joinPoint);
        }
    }

    private static void processRequestWithRawSqlQuery(JoinPoint joinPoint) throws SQLException {
        String sqlString = getRawSqlQueryFromJointPoint(joinPoint);
        Boolean hasQueryParameter = isQueryParameterized(joinPoint);

        if(hasQueryParameter){
            processQueriesWithParameters(sqlString, joinPoint);
        }else{
            processQueriesWithoutParameters(sqlString, joinPoint);
        }

        /**
         * 1. how to run raw sql on shard
         * 2. How to implement scatter method of querying shard in distributed system
         * 3. Implement Entity With UUId
         */

    }

    private static void processRequestWithoutRawSqlQuery(JoinPoint joinPoint) throws IllegalAccessException {
        Object[] args = joinPoint.getArgs();
        if(args.length == 0){
            // if request comes without arg, like findAll

            /*
             * To implement query broadcasting to all shards, perform query on each
             * shard and join the result as shown
             * select * from db1.t1
             * union
             * select * from db2.t2
             *
             * The main problem is that if you run into is cross server joins,
             * on large million + row systems, it can hit the network pretty
             * hard and take a long time to process queries.
             *
             */


        }else if(args.length == 1){
            // if request comes in with only one arg like findById or save etc
            Object arg = args[0];
            if(arg instanceof String || arg instanceof  Number){
                // if the arg is just an ID like UUID or from snowflake like in findById etc
                selectShardForSingleArg(arg);

                /* TODO:  Handle queries with single method arguments that are not Primary key
                 *
                 * Note there are repository methods like
                 * Optional<StaffMemberEntity> findByStaffEmail(String staffEmail);
                 * that the provided argument needs to be broadcast across all the shards
                 */

            }else{
                // if the arg is an entity like in save
                selectShardForSingleArg(arg);
            }
        }else {
            /*
             * There are situations where multiple arguments come in but only one of them
             * which may be int (or string) or an object (with an id field) that will
             * determine the shard to operate on.
             */

            selectShardForMultipleArgs(args);
        }
    }

    private static Boolean isQueryParameterized(JoinPoint joinPoint){
        return joinPoint.getArgs().length > 0;
    }

    private static void processQueriesWithoutParameters (String sqlString, JoinPoint joinPoint) throws SQLException {
        broadCastQueryWithoutParametersAcrossShards(sqlString, joinPoint);
    }

    private static void processQueriesWithParameters (String sqlString, JoinPoint joinPoint) throws SQLException {
        PreparedStatement preparedStatement = broadCastQueryWithParameterAcrossShards(sqlString);

        // broadCastQueryAcrossShards should return something that we can
        // then set the parameters with.
    }

    private static void broadCastQueryWithoutParametersAcrossShards(String sqlString, JoinPoint joinPoint) throws SQLException {
        Map<Object, Object> shardMap = DataSourcesHandlerAspect.getDataSourceMap();

        /*
         * A ResultSet object is automatically closed when the Statement object that
         * generated it is closed, re-executed, or used to retrieve the next result
         * from a sequence of multiple results.
         */

        // why use resultSet set here
        List<ResultSet> results = new ArrayList<>();

        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;

        for(Object shardKey: shardMap.keySet()){
            DataSource dataSource = (DataSource) shardMap.get(shardKey);
            try{
                /*
                 * The reason for calling dataSource.getConnection()
                 * is that the DataSource object itself does not represent a direct,
                 * persistent connection to the database. Instead, it acts as a factory
                 * for providing pooled connections.
                 *
                 * DataSource provides connection pooling, where multiple connections are
                 * kept alive and reused. When you call getConnection(), you're borrowing a
                 * pre-existing connection from this pool.
                 *
                 * After using a connection, it is returned to the pool when it is closed in the finally block.
                 * The pool manages these connections, so we are not disconnecting from the database, just
                 * returning the connection to the pool for the next use.
                 *
                 */
                connection = dataSource.getConnection();

                /*
                 * Here we are using a PreparedStatement object for sending parameterized
                 * SQL statements to the database. Recall that parameterized statement are
                 * statements that are meant to be reused very often with different parameter.
                 * Thus, it is not suitable to use Statement object which is designed for normal
                 * statements according documentation on the Statement object that says
                 * "If the same SQL statement is executed many times, it may be more
                 * efficient to use a PreparedStatement object."
                 */
                preparedStatement = connection.prepareStatement(sqlString);
                resultSet = preparedStatement.executeQuery();
                results.add(resultSet);
            }catch (Exception e){
                logger.error("Error performing operation on shard: {}", shardKey);
            }finally {
                /*
                 * closes the connection and makes it available for any other component
                 * in the app. That is makes it idle and returns it to the pool.
                 *
                 * Note resources are passed in order the expecting are expected
                 * and try-with-resources statement can eliminate the need for this
                 * resource closing util class, but I still implemented it for
                 * learning purpose.
                 */
                ResourceCloser.closeResources(connection, preparedStatement, resultSet);
            }
        }
        List<Map<String, Object>> combinedResults = combineQueryResults(results);
        Object transformedResult = transformResultSetToAppropriateReturnType(combinedResults, joinPoint); // for example map to entity

        /*
         * After combining the result we need to ensure the result is sent from the repository
         * layer back to the service layer in the expected format.
         */
    }

    private static List<Map<String, Object>> broadCastQueryWithParameterAcrossShards (String sqlString) throws SQLException {
        //Object[] shards = DataSourcesHandlerAspect.getShardList();
        Map<Object, Object> shardMap = DataSourcesHandlerAspect.getDataSourceMap();

        // why use resultSet set here
        List<ResultSet> results = new ArrayList<>();

        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;

        for(Object shardKey: shardMap.keySet()){
            DataSource dataSource = (DataSource) shardMap.get(shardKey);
            try{
                /*
                 * The reason for calling dataSource.getConnection()
                 * is that the DataSource object itself does not represent a direct,
                 * persistent connection to the database. Instead, it acts as a factory
                 * for providing pooled connections.
                 *
                 * DataSource provides connection pooling, where multiple connections are
                 * kept alive and reused. When you call getConnection(), you're borrowing a
                 * pre-existing connection from this pool.
                 *
                 * After using a connection, it is returned to the pool when it is closed in the finally block.
                 * The pool manages these connections, so we are not disconnecting from the database, just
                 * returning the connection to the pool for the next use.
                 *
                 */
                connection = dataSource.getConnection();

                /*
                 * Here we are using a PreparedStatement object for sending parameterized
                 * SQL statements to the database. Recall that parameterized statement are
                 * statements that are meant to be reused very often with different parameter.
                 * Thus, it is not suitable to use Statement object which is designed for normal
                 * statements according documentation on the Statement object that says
                 * "If the same SQL statement is executed many times, it may be more
                 * efficient to use a PreparedStatement object."
                 */
                preparedStatement = connection.prepareStatement(sqlString);
                resultSet = preparedStatement.executeQuery(sqlString);

                // If you have parameters to set, do it here
                // Example: preparedStatement.setString(1, "someValue");

                // combine results from each shard

            }catch (Exception e){
                logger.error("Error performing operation on shard: {}", shardKey);
            }finally {
                /*
                 * closes the connection and makes it available for any other component
                 * in the app. That is makes it idle and returns it to the pool.
                 *
                 * Note resources are passed in order the expecting are expected
                 * and try-with-resources statement can eliminate the need for this
                 * resource closing util class, but I still implemented it for
                 * learning purpose.
                 */
                ResourceCloser.closeResources(connection, preparedStatement, resultSet);
            }

        }
    }

    private static List<Map<String, Object>> combineQueryResults(List<ResultSet> results) throws SQLException {

        /*
         * We have to loop through everything because a result set is not updatable
         * according to this portion of the documentation
         * "A default ResultSet object is not updatable and has a cursor that moves forward only."
         */

        List<Map<String, Object>> combinedResults = new ArrayList<>();
        for (ResultSet resultSet: results){
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount  = metaData.getColumnCount();

            while(resultSet.next()){
                Map<String, Object> row = new HashMap<>();

                for(int i=1; i<=columnCount; i++){
                    String columnName = metaData.getColumnName(i);
                    Object value = resultSet.getObject(i);
                    row.put(columnName, value);
                }
                combinedResults.add(row);
            }
        }
        return combinedResults;
    }

    private static Object transformResultSetToAppropriateReturnType(List<Map<String, Object>> combinedResults, JoinPoint joinPoint ){
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Class<?> returnedType = methodSignature.getReturnType();

        // use ParameterizedType instead of the code below
//        returnedType.getName();

    }

    private static String getRawSqlQueryFromJointPoint(JoinPoint joinPoint){
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        if(method.isAnnotationPresent(Query.class)){
            Query queryAnnotation = method.getAnnotation(Query.class);
            return queryAnnotation.value();
        }
        return null;
    }

    private static Boolean isAnnotatedWithQuery(JoinPoint joinPoint){
        Annotation[] repositoryMethodAnnotations = getRepositoryMethodAnnotation(joinPoint);
        for(Annotation annotation: repositoryMethodAnnotations){
            if(annotation.annotationType() == Query.class){
                return true;
            }
        }
        return false;
    }

    private static Annotation[] getRepositoryMethodAnnotation(JoinPoint joinPoint){
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return method.getAnnotations();
    }

    @After("execution (* com.piraxx.sharder.repositories..*(..))")
    private void clearShardingContext() {
        ShardingContextHolder.clear();
    }

    private static void selectShardForMultipleArgs(Object[] args){
        for (Object arg: args){
            if(arg instanceof TransactionEntity){
                TransactionEntity transactionEntity = (TransactionEntity) arg;
                String shardKey = determineShard(transactionEntity.getTransactionId());
                ShardingContextHolder.setCurrentShardKey(shardKey);
            }
        }
    }

    private static void selectShardForSingleArg(Object arg) throws IllegalAccessException {
            Class<?> clazz = arg.getClass();
            if(clazz.isAnnotationPresent(Entity.class)){
                // if argument is an entity
                String shardKey = determineShard(getIdFieldValue((arg)));
                ShardingContextHolder.setCurrentShardKey(shardKey);

            }else{
                // if argument is a string or a number
                String shardKey = determineShard(arg);
                ShardingContextHolder.setCurrentShardKey(shardKey);
            }
    }

    private static Object getIdFieldValue(Object entityInstance ) throws IllegalAccessException {

        Class<?> entityClass =  entityInstance.getClass();
        for(Field field: entityClass.getDeclaredFields()){

            if(field.isAnnotationPresent(Id.class)){
                field.setAccessible(true);
                return field.get(entityInstance);
            }
        }
        throw new IllegalArgumentException("No field annotated in class of " + entityClass.getName());
    }

    private static String determineShard(Object obj){
        return consistentHashing.getNode(obj);
    }
}
