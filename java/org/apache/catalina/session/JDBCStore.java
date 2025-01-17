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

package org.apache.catalina.session;

import org.apache.catalina.*;
import org.apache.catalina.util.CustomObjectInputStream;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Properties;

/**
 * Implementation of the <code>Store</code> interface that stores
 * serialized session objects in a database.  Sessions that are
 * saved are still subject to being expired based on inactivity.
 *
 * @author Bip Thelin
 */

public class JDBCStore
        extends StoreBase implements Store
{

    /**
     * The descriptive information about this implementation.
     */
    protected static String info = "JDBCStore/1.0";
    /**
     * Name to register for this Store, used for logging.
     */
    protected static String storeName = "JDBCStore";
    /**
     * Name to register for the background thread.
     */
    protected String threadName = "JDBCStore";
    /**
     * The connection username to use when trying to connect to the database.
     */
    protected String connectionName = null;
    /**
     * The connection URL to use when trying to connect to the database.
     */
    protected String connectionPassword = null;
    /**
     * Connection string to use when connecting to the DB.
     */
    protected String connectionURL = null;
    /**
     * Instance of the JDBC Driver class we use as a connection factory.
     */
    protected Driver driver = null;
    /**
     * Driver to use.
     */
    protected String driverName = null;
    /**
     * Table to use.
     */
    protected String sessionTable = "tomcat$sessions";
    /**
     * Column to use for /Engine/Host/Context name
     */
    protected String sessionAppCol = "app";

    // ------------------------------------------------------------- Table & cols
    /**
     * Id column to use.
     */
    protected String sessionIdCol = "id";
    /**
     * Data column to use.
     */
    protected String sessionDataCol = "data";
    /**
     * Is Valid column to use.
     */
    protected String sessionValidCol = "valid";
    /**
     * Max Inactive column to use.
     */
    protected String sessionMaxInactiveCol = "maxinactive";
    /**
     * Last Accessed column to use.
     */
    protected String sessionLastAccessedCol = "lastaccess";
    /**
     * Variable to hold the <code>getSize()</code> prepared statement.
     */
    protected PreparedStatement preparedSizeSql = null;
    /**
     * Variable to hold the <code>keys()</code> prepared statement.
     */
    protected PreparedStatement preparedKeysSql = null;

    // ------------------------------------------------------------- SQL Variables
    /**
     * Variable to hold the <code>save()</code> prepared statement.
     */
    protected PreparedStatement preparedSaveSql = null;
    /**
     * Variable to hold the <code>clear()</code> prepared statement.
     */
    protected PreparedStatement preparedClearSql = null;
    /**
     * Variable to hold the <code>remove()</code> prepared statement.
     */
    protected PreparedStatement preparedRemoveSql = null;
    /**
     * Variable to hold the <code>load()</code> prepared statement.
     */
    protected PreparedStatement preparedLoadSql = null;
    /**
     * Context name associated with this Store
     */
    private String name = null;
    /**
     * The database connection.
     */
    private Connection dbConnection = null;

    // ------------------------------------------------------------- Properties

    /**
     * Return the info for this Store.
     */
    public String getInfo()
    {
        return (info);
    }

    /**
     * Return the name for this instance (built from container name)
     */
    public String getName()
    {
        if (name == null)
        {
            Container container = manager.getContainer();
            String contextName = container.getName();
            String hostName = "";
            String engineName = "";

            if (container.getParent() != null)
            {
                Container host = container.getParent();
                hostName = host.getName();
                if (host.getParent() != null)
                {
                    engineName = host.getParent().getName();
                }
            }
            name = "/" + engineName + "/" + hostName + contextName;
        }
        return name;
    }

    /**
     * Return the thread name for this Store.
     */
    public String getThreadName()
    {
        return (threadName);
    }

    /**
     * Return the name for this Store, used for logging.
     */
    public String getStoreName()
    {
        return (storeName);
    }

    /**
     * Return the driver for this Store.
     */
    public String getDriverName()
    {
        return (this.driverName);
    }

    /**
     * Set the driver for this Store.
     *
     * @param driverName The new driver
     */
    public void setDriverName(String driverName)
    {
        String oldDriverName = this.driverName;
        this.driverName = driverName;
        support.firePropertyChange("driverName",
                oldDriverName,
                this.driverName);
        this.driverName = driverName;
    }

    /**
     * Return the username to use to connect to the database.
     */
    public String getConnectionName()
    {
        return connectionName;
    }

    /**
     * Set the username to use to connect to the database.
     *
     * @param connectionName Username
     */
    public void setConnectionName(String connectionName)
    {
        this.connectionName = connectionName;
    }

    /**
     * Return the password to use to connect to the database.
     */
    public String getConnectionPassword()
    {
        return connectionPassword;
    }

    /**
     * Set the password to use to connect to the database.
     *
     * @param connectionPassword User password
     */
    public void setConnectionPassword(String connectionPassword)
    {
        this.connectionPassword = connectionPassword;
    }

    /**
     * Return the Connection URL for this Store.
     */
    public String getConnectionURL()
    {
        return (this.connectionURL);
    }

    /**
     * Set the Connection URL for this Store.
     *
     * @param connectionURL The new Connection URL
     */
    public void setConnectionURL(String connectionURL)
    {
        String oldConnString = this.connectionURL;
        this.connectionURL = connectionURL;
        support.firePropertyChange("connectionURL",
                oldConnString,
                this.connectionURL);
    }

    /**
     * Return the table for this Store.
     */
    public String getSessionTable()
    {
        return (this.sessionTable);
    }

    /**
     * Set the table for this Store.
     *
     * @param sessionTable The new table
     */
    public void setSessionTable(String sessionTable)
    {
        String oldSessionTable = this.sessionTable;
        this.sessionTable = sessionTable;
        support.firePropertyChange("sessionTable",
                oldSessionTable,
                this.sessionTable);
    }

    /**
     * Return the web application name column for the table.
     */
    public String getSessionAppCol()
    {
        return (this.sessionAppCol);
    }

    /**
     * Set the App column for the table.
     *
     * @param sessionAppCol the column name
     */
    public void setSessionAppCol(String sessionAppCol)
    {
        String oldSessionAppCol = this.sessionAppCol;
        this.sessionAppCol = sessionAppCol;
        support.firePropertyChange("sessionAppCol",
                oldSessionAppCol,
                this.sessionAppCol);
    }

    /**
     * Return the Id column for the table.
     */
    public String getSessionIdCol()
    {
        return (this.sessionIdCol);
    }

    /**
     * Set the Id column for the table.
     *
     * @param sessionIdCol the column name
     */
    public void setSessionIdCol(String sessionIdCol)
    {
        String oldSessionIdCol = this.sessionIdCol;
        this.sessionIdCol = sessionIdCol;
        support.firePropertyChange("sessionIdCol",
                oldSessionIdCol,
                this.sessionIdCol);
    }

    /**
     * Return the data column for the table
     */
    public String getSessionDataCol()
    {
        return (this.sessionDataCol);
    }

    /**
     * Set the Data column for the table
     *
     * @param sessionDataCol the column name
     */
    public void setSessionDataCol(String sessionDataCol)
    {
        String oldSessionDataCol = this.sessionDataCol;
        this.sessionDataCol = sessionDataCol;
        support.firePropertyChange("sessionDataCol",
                oldSessionDataCol,
                this.sessionDataCol);
    }

    /**
     * Return the Is Valid column
     */
    public String getSessionValidCol()
    {
        return (this.sessionValidCol);
    }

    /**
     * Set the Is Valid column for the table
     *
     * @param sessionValidCol The column name
     */
    public void setSessionValidCol(String sessionValidCol)
    {
        String oldSessionValidCol = this.sessionValidCol;
        this.sessionValidCol = sessionValidCol;
        support.firePropertyChange("sessionValidCol",
                oldSessionValidCol,
                this.sessionValidCol);
    }

    /**
     * Return the Max Inactive column
     */
    public String getSessionMaxInactiveCol()
    {
        return (this.sessionMaxInactiveCol);
    }

    /**
     * Set the Max Inactive column for the table
     *
     * @param sessionMaxInactiveCol The column name
     */
    public void setSessionMaxInactiveCol(String sessionMaxInactiveCol)
    {
        String oldSessionMaxInactiveCol = this.sessionMaxInactiveCol;
        this.sessionMaxInactiveCol = sessionMaxInactiveCol;
        support.firePropertyChange("sessionMaxInactiveCol",
                oldSessionMaxInactiveCol,
                this.sessionMaxInactiveCol);
    }

    /**
     * Return the Last Accessed column
     */
    public String getSessionLastAccessedCol()
    {
        return (this.sessionLastAccessedCol);
    }

    /**
     * Set the Last Accessed column for the table
     *
     * @param sessionLastAccessedCol The column name
     */
    public void setSessionLastAccessedCol(String sessionLastAccessedCol)
    {
        String oldSessionLastAccessedCol = this.sessionLastAccessedCol;
        this.sessionLastAccessedCol = sessionLastAccessedCol;
        support.firePropertyChange("sessionLastAccessedCol",
                oldSessionLastAccessedCol,
                this.sessionLastAccessedCol);
    }

    // --------------------------------------------------------- Public Methods

    /**
     * Return an array containing the session identifiers of all Sessions
     * currently saved in this Store.  If there are no such Sessions, a
     * zero-length array is returned.
     *
     * @throws IOException if an input/output error occurred
     */
    public String[] keys() throws IOException
    {
        ResultSet rst = null;
        String keys[] = null;
        synchronized (this)
        {
            int numberOfTries = 2;
            while (numberOfTries > 0)
            {

                Connection _conn = getConnection();
                if (_conn == null)
                {
                    return (new String[0]);
                }
                try
                {
                    if (preparedKeysSql == null)
                    {
                        String keysSql = "SELECT " + sessionIdCol + " FROM "
                                + sessionTable + " WHERE " + sessionAppCol
                                + " = ?";
                        preparedKeysSql = _conn.prepareStatement(keysSql);
                    }

                    preparedKeysSql.setString(1, getName());
                    rst = preparedKeysSql.executeQuery();
                    ArrayList tmpkeys = new ArrayList();
                    if (rst != null)
                    {
                        while (rst.next())
                        {
                            tmpkeys.add(rst.getString(1));
                        }
                    }
                    keys = (String[]) tmpkeys.toArray(new String[tmpkeys.size()]);
                    // Break out after the finally block
                    numberOfTries = 0;
                }
                catch (SQLException e)
                {
                    manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".SQLException", e));
                    keys = new String[0];
                    // Close the connection so that it gets reopened next time
                    if (dbConnection != null)
                        close(dbConnection);
                }
                finally
                {
                    try
                    {
                        if (rst != null)
                        {
                            rst.close();
                        }
                    }
                    catch (SQLException e)
                    {
                        ;
                    }

                    release(_conn);
                }
                numberOfTries--;
            }
        }

        return (keys);
    }

    /**
     * Return an integer containing a count of all Sessions
     * currently saved in this Store.  If there are no Sessions,
     * <code>0</code> is returned.
     *
     * @throws IOException if an input/output error occurred
     */
    public int getSize() throws IOException
    {
        int size = 0;
        ResultSet rst = null;

        synchronized (this)
        {
            int numberOfTries = 2;
            while (numberOfTries > 0)
            {
                Connection _conn = getConnection();

                if (_conn == null)
                {
                    return (size);
                }

                try
                {
                    if (preparedSizeSql == null)
                    {
                        String sizeSql = "SELECT COUNT(" + sessionIdCol
                                + ") FROM " + sessionTable + " WHERE "
                                + sessionAppCol + " = ?";
                        preparedSizeSql = _conn.prepareStatement(sizeSql);
                    }

                    preparedSizeSql.setString(1, getName());
                    rst = preparedSizeSql.executeQuery();
                    if (rst.next())
                    {
                        size = rst.getInt(1);
                    }
                    // Break out after the finally block
                    numberOfTries = 0;
                }
                catch (SQLException e)
                {
                    manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".SQLException", e));
                    if (dbConnection != null)
                        close(dbConnection);
                }
                finally
                {
                    try
                    {
                        if (rst != null)
                            rst.close();
                    }
                    catch (SQLException e)
                    {
                        ;
                    }

                    release(_conn);
                }
                numberOfTries--;
            }
        }
        return (size);
    }

    /**
     * Load the Session associated with the id <code>id</code>.
     * If no such session is found <code>null</code> is returned.
     *
     * @param id a value of type <code>String</code>
     * @return the stored <code>Session</code>
     * @throws ClassNotFoundException if an error occurs
     * @throws IOException            if an input/output error occurred
     */
    public Session load(String id)
            throws ClassNotFoundException, IOException
    {
        ResultSet rst = null;
        StandardSession _session = null;
        Loader loader = null;
        ClassLoader classLoader = null;
        ObjectInputStream ois = null;
        BufferedInputStream bis = null;
        Container container = manager.getContainer();

        synchronized (this)
        {
            int numberOfTries = 2;
            while (numberOfTries > 0)
            {
                Connection _conn = getConnection();
                if (_conn == null)
                {
                    return (null);
                }

                try
                {
                    if (preparedLoadSql == null)
                    {
                        String loadSql = "SELECT " + sessionIdCol + ", "
                                + sessionDataCol + " FROM " + sessionTable
                                + " WHERE " + sessionIdCol + " = ? AND "
                                + sessionAppCol + " = ?";
                        preparedLoadSql = _conn.prepareStatement(loadSql);
                    }

                    preparedLoadSql.setString(1, id);
                    preparedLoadSql.setString(2, getName());
                    rst = preparedLoadSql.executeQuery();
                    if (rst.next())
                    {
                        bis = new BufferedInputStream(rst.getBinaryStream(2));

                        if (container != null)
                        {
                            loader = container.getLoader();
                        }
                        if (loader != null)
                        {
                            classLoader = loader.getClassLoader();
                        }
                        if (classLoader != null)
                        {
                            ois = new CustomObjectInputStream(bis,
                                    classLoader);
                        } else
                        {
                            ois = new ObjectInputStream(bis);
                        }

                        if (manager.getContainer().getLogger().isDebugEnabled())
                        {
                            manager.getContainer().getLogger().debug(sm.getString(getStoreName() + ".loading",
                                    id, sessionTable));
                        }

                        _session = (StandardSession) manager.createEmptySession();
                        _session.readObjectData(ois);
                        _session.setManager(manager);
                    } else if (manager.getContainer().getLogger().isDebugEnabled())
                    {
                        manager.getContainer().getLogger().debug(getStoreName() + ": No persisted data object found");
                    }
                    // Break out after the finally block
                    numberOfTries = 0;
                }
                catch (SQLException e)
                {
                    manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".SQLException", e));
                    if (dbConnection != null)
                        close(dbConnection);
                }
                finally
                {
                    try
                    {
                        if (rst != null)
                        {
                            rst.close();
                        }
                    }
                    catch (SQLException e)
                    {
                        ;
                    }
                    if (ois != null)
                    {
                        try
                        {
                            ois.close();
                        }
                        catch (IOException e)
                        {
                            ;
                        }
                    }
                    release(_conn);
                }
                numberOfTries--;
            }
        }

        return (_session);
    }

    /**
     * Remove the Session with the specified session identifier from
     * this Store, if present.  If no such Session is present, this method
     * takes no action.
     *
     * @param id Session identifier of the Session to be removed
     * @throws IOException if an input/output error occurs
     */
    public void remove(String id) throws IOException
    {

        synchronized (this)
        {
            int numberOfTries = 2;
            while (numberOfTries > 0)
            {
                Connection _conn = getConnection();

                if (_conn == null)
                {
                    return;
                }

                try
                {
                    if (preparedRemoveSql == null)
                    {
                        String removeSql = "DELETE FROM " + sessionTable
                                + " WHERE " + sessionIdCol + " = ?  AND "
                                + sessionAppCol + " = ?";
                        preparedRemoveSql = _conn.prepareStatement(removeSql);
                    }

                    preparedRemoveSql.setString(1, id);
                    preparedRemoveSql.setString(2, getName());
                    preparedRemoveSql.execute();
                    // Break out after the finally block
                    numberOfTries = 0;
                }
                catch (SQLException e)
                {
                    manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".SQLException", e));
                    if (dbConnection != null)
                        close(dbConnection);
                }
                finally
                {
                    release(_conn);
                }
                numberOfTries--;
            }
        }

        if (manager.getContainer().getLogger().isDebugEnabled())
        {
            manager.getContainer().getLogger().debug(sm.getString(getStoreName() + ".removing", id, sessionTable));
        }
    }

    /**
     * Remove all of the Sessions in this Store.
     *
     * @throws IOException if an input/output error occurs
     */
    public void clear() throws IOException
    {

        synchronized (this)
        {
            int numberOfTries = 2;
            while (numberOfTries > 0)
            {
                Connection _conn = getConnection();
                if (_conn == null)
                {
                    return;
                }

                try
                {
                    if (preparedClearSql == null)
                    {
                        String clearSql = "DELETE FROM " + sessionTable
                                + " WHERE " + sessionAppCol + " = ?";
                        preparedClearSql = _conn.prepareStatement(clearSql);
                    }

                    preparedClearSql.setString(1, getName());
                    preparedClearSql.execute();
                    // Break out after the finally block
                    numberOfTries = 0;
                }
                catch (SQLException e)
                {
                    manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".SQLException", e));
                    if (dbConnection != null)
                        close(dbConnection);
                }
                finally
                {
                    release(_conn);
                }
                numberOfTries--;
            }
        }
    }

    /**
     * Save a session to the Store.
     *
     * @param session the session to be stored
     * @throws IOException if an input/output error occurs
     */
    public void save(Session session) throws IOException
    {
        ObjectOutputStream oos = null;
        ByteArrayOutputStream bos = null;
        ByteArrayInputStream bis = null;
        InputStream in = null;

        synchronized (this)
        {
            int numberOfTries = 2;
            while (numberOfTries > 0)
            {
                Connection _conn = getConnection();
                if (_conn == null)
                {
                    return;
                }

                // If sessions already exist in DB, remove and insert again.
                // TODO:
                // * Check if ID exists in database and if so use UPDATE.
                remove(session.getIdInternal());

                try
                {
                    bos = new ByteArrayOutputStream();
                    oos = new ObjectOutputStream(new BufferedOutputStream(bos));

                    ((StandardSession) session).writeObjectData(oos);
                    oos.close();
                    oos = null;
                    byte[] obs = bos.toByteArray();
                    int size = obs.length;
                    bis = new ByteArrayInputStream(obs, 0, size);
                    in = new BufferedInputStream(bis, size);

                    if (preparedSaveSql == null)
                    {
                        String saveSql = "INSERT INTO " + sessionTable + " ("
                                + sessionIdCol + ", " + sessionAppCol + ", "
                                + sessionDataCol + ", " + sessionValidCol
                                + ", " + sessionMaxInactiveCol + ", "
                                + sessionLastAccessedCol
                                + ") VALUES (?, ?, ?, ?, ?, ?)";
                        preparedSaveSql = _conn.prepareStatement(saveSql);
                    }

                    preparedSaveSql.setString(1, session.getIdInternal());
                    preparedSaveSql.setString(2, getName());
                    preparedSaveSql.setBinaryStream(3, in, size);
                    preparedSaveSql.setString(4, session.isValid() ? "1" : "0");
                    preparedSaveSql.setInt(5, session.getMaxInactiveInterval());
                    preparedSaveSql.setLong(6, session.getLastAccessedTime());
                    preparedSaveSql.execute();
                    // Break out after the finally block
                    numberOfTries = 0;
                }
                catch (SQLException e)
                {
                    manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".SQLException", e));
                    if (dbConnection != null)
                        close(dbConnection);
                }
                catch (IOException e)
                {
                    ;
                }
                finally
                {
                    if (oos != null)
                    {
                        oos.close();
                    }
                    if (bis != null)
                    {
                        bis.close();
                    }
                    if (in != null)
                    {
                        in.close();
                    }

                    release(_conn);
                }
                numberOfTries--;
            }
        }

        if (manager.getContainer().getLogger().isDebugEnabled())
        {
            manager.getContainer().getLogger().debug(sm.getString(getStoreName() + ".saving",
                    session.getIdInternal(), sessionTable));
        }
    }

    // --------------------------------------------------------- Protected Methods

    /**
     * Check the connection associated with this store, if it's
     * <code>null</code> or closed try to reopen it.
     * Returns <code>null</code> if the connection could not be established.
     *
     * @return <code>Connection</code> if the connection suceeded
     */
    protected Connection getConnection()
    {
        try
        {
            if (dbConnection == null || dbConnection.isClosed())
            {
                manager.getContainer().getLogger().info(sm.getString(getStoreName() + ".checkConnectionDBClosed"));
                open();
                if (dbConnection == null || dbConnection.isClosed())
                {
                    manager.getContainer().getLogger().info(sm.getString(getStoreName() + ".checkConnectionDBReOpenFail"));
                }
            }
        }
        catch (SQLException ex)
        {
            manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".checkConnectionSQLException",
                    ex.toString()));
        }

        return dbConnection;
    }

    /**
     * Open (if necessary) and return a database connection for use by
     * this Realm.
     *
     * @throws SQLException if a database error occurs
     */
    protected Connection open() throws SQLException
    {

        // Do nothing if there is a database connection already open
        if (dbConnection != null)
            return (dbConnection);

        // Instantiate our database driver if necessary
        if (driver == null)
        {
            try
            {
                Class clazz = Class.forName(driverName);
                driver = (Driver) clazz.newInstance();
            }
            catch (ClassNotFoundException ex)
            {
                manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".checkConnectionClassNotFoundException",
                        ex.toString()));
            }
            catch (InstantiationException ex)
            {
                manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".checkConnectionClassNotFoundException",
                        ex.toString()));
            }
            catch (IllegalAccessException ex)
            {
                manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".checkConnectionClassNotFoundException",
                        ex.toString()));
            }
        }

        // Open a new connection
        Properties props = new Properties();
        if (connectionName != null)
            props.put("user", connectionName);
        if (connectionPassword != null)
            props.put("password", connectionPassword);
        dbConnection = driver.connect(connectionURL, props);
        dbConnection.setAutoCommit(true);
        return (dbConnection);

    }

    /**
     * Close the specified database connection.
     *
     * @param dbConnection The connection to be closed
     */
    protected void close(Connection dbConnection)
    {

        // Do nothing if the database connection is already closed
        if (dbConnection == null)
            return;

        // Close our prepared statements (if any)
        try
        {
            preparedSizeSql.close();
        }
        catch (Throwable f)
        {
            ;
        }
        this.preparedSizeSql = null;

        try
        {
            preparedKeysSql.close();
        }
        catch (Throwable f)
        {
            ;
        }
        this.preparedKeysSql = null;

        try
        {
            preparedSaveSql.close();
        }
        catch (Throwable f)
        {
            ;
        }
        this.preparedSaveSql = null;

        try
        {
            preparedClearSql.close();
        }
        catch (Throwable f)
        {
            ;
        }

        try
        {
            preparedRemoveSql.close();
        }
        catch (Throwable f)
        {
            ;
        }
        this.preparedRemoveSql = null;

        try
        {
            preparedLoadSql.close();
        }
        catch (Throwable f)
        {
            ;
        }
        this.preparedLoadSql = null;

        // Close this database connection, and log any errors
        try
        {
            dbConnection.close();
        }
        catch (SQLException e)
        {
            manager.getContainer().getLogger().error(sm.getString(getStoreName() + ".close", e.toString())); // Just log it here
        }
        finally
        {
            this.dbConnection = null;
        }

    }

    /**
     * Release the connection, not needed here since the
     * connection is not associated with a connection pool.
     *
     * @param conn The connection to be released
     */
    protected void release(Connection conn)
    {
        ;
    }

    /**
     * Called once when this Store is first started.
     */
    public void start() throws LifecycleException
    {
        super.start();

        // Open connection to the database
        this.dbConnection = getConnection();
    }

    /**
     * Gracefully terminate everything associated with our db.
     * Called once when this Store is stopping.
     */
    public void stop() throws LifecycleException
    {
        super.stop();

        // Close and release everything associated with our db.
        if (dbConnection != null)
        {
            try
            {
                dbConnection.commit();
            }
            catch (SQLException e)
            {
                ;
            }
            close(dbConnection);
        }
    }
}
