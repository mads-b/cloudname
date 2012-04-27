package org.cloudname.zk;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.cloudname.*;
import org.cloudname.testtools.Net;
import org.cloudname.testtools.zookeeper.EmbeddedZooKeeper;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;


/**
 * This class contains the unit tests for the ZkResolver class.
 *
 * TODO(borud): add tests for when the input is a coordinate.
 *
 * @author borud
 */
public class ZkResolverTest {
    private EmbeddedZooKeeper ezk;
    private ZooKeeper zk;
    private int zkport;
    private ZkCloudname cn;
    private Coordinate coordinateRunning;
    private Coordinate coordinateDraining;
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private ServiceHandle handleDraining;
    
    /**
     * Set up an embedded ZooKeeper instance backed by a temporary
     * directory.  The setup procedure also allocates a port that is
     * free for the ZooKeeper server so that you should be able to run
     * multiple instances of this test.
     */
    @Before
    public void setup() throws Exception {
        File rootDir = temp.newFolder("zk-test");
        zkport = Net.getFreePort();

        // Set up and initialize the embedded ZooKeeper
        ezk = new EmbeddedZooKeeper(rootDir, zkport);
        ezk.init();

        // Set up a zookeeper client that we can use for inspection
        final CountDownLatch connectedLatch = new CountDownLatch(1);
        zk = new ZooKeeper("localhost:" + zkport, 1000, new Watcher() {
            public void process(WatchedEvent event) {
                if (event.getState() == Event.KeeperState.SyncConnected) {
                    connectedLatch.countDown();
                }
            }
        });
        connectedLatch.await();
        coordinateRunning = Coordinate.parse("1.service.user.cell");
        cn = new ZkCloudname.Builder().setConnectString("localhost:" + zkport).build().connect();
        cn.createCoordinate(coordinateRunning);
        ServiceHandle handleRunning = cn.claim(coordinateRunning);
        assertTrue(handleRunning.waitForCoordinateOkSeconds(30));

        handleRunning.putEndpoint(new Endpoint(coordinateRunning, "foo", "localhost", 1234, "http", "data"));
        handleRunning.putEndpoint(new Endpoint(coordinateRunning, "bar", "localhost", 1235, "http", null));
        ServiceStatus statusRunning = new ServiceStatus(ServiceState.RUNNING, "Running message");
        handleRunning.setStatus(statusRunning);

        coordinateDraining = Coordinate.parse("0.service.user.cell");
        cn.createCoordinate(coordinateDraining);
        handleDraining = cn.claim(coordinateDraining);
        assertTrue(handleDraining.waitForCoordinateOkSeconds(10));
        handleDraining.putEndpoint(new Endpoint(coordinateDraining, "foo", "localhost", 5555, "http", "data"));
        handleDraining.putEndpoint(new Endpoint(coordinateDraining, "bar", "localhost", 5556, "http", null));

        ServiceStatus statusDraining = new ServiceStatus(ServiceState.DRAINING, "Draining message");
        handleDraining.setStatus(statusDraining);
    }

    public void undrain() throws CoordinateMissingException, CloudnameException {
        ServiceStatus statusDraining = new ServiceStatus(ServiceState.RUNNING, "alive");
        handleDraining.setStatus(statusDraining);
    }

    public void drain() throws CoordinateMissingException, CloudnameException {
        ServiceStatus statusDraining = new ServiceStatus(ServiceState.DRAINING, "dead");
        handleDraining.setStatus(statusDraining);
    }

    public void changeEndpoint() throws CoordinateMissingException, CloudnameException {
        handleDraining.removeEndpoint("foo");
        handleDraining.putEndpoint(new Endpoint(coordinateDraining, "foo", "localhost", 4, "http", "data"));

    }

    @After
    public void tearDown() throws Exception {
        zk.close();
    }
    // Valid endpoints.
    public static final String[] validEndpointPatterns = new String[] {
        "http.1.service.user.cell",
        "foo-bar.3245.service.user.cell",
        "foo_bar.3245.service.user.cell",
        "foo_bar.3245.service.user.cell",
    };

    // Valid strategy.
    public static final String[] validStrategyPatterns = new String[] {
        "any.service.user.cell",
        "all.service.user.cell",
        "somestrategy.service.user.cell",
    };

    // Valid endpoint strategy.
    public static final String[] validEndpointStrategyPatterns = new String[] {
        "http.any.service.user.cell",
        "thrift.all.service.user.cell",
        "some-endpoint.somestrategy.service.user.cell",
    };

    @Test
    public void testStatus() throws Exception {
        ServiceStatus status = cn.getStatus(coordinateRunning);
        assertEquals(ServiceState.RUNNING, status.getState());

        assertEquals("Running message", status.getMessage());
    }
    
    
    @Test
    public void testBasicSyncResolving() throws Exception {
        Resolver resolver = cn.getResolver();
        List<Endpoint> endpoints = resolver.resolve("foo.1.service.user.cell");
        assertEquals(1, endpoints.size());
        assertEquals("foo", endpoints.get(0).getName());
        assertEquals("localhost", endpoints.get(0).getHost());
        assertEquals("1.service.user.cell", endpoints.get(0).getCoordinate().toString());
        assertEquals("data", endpoints.get(0).getEndpointData());
        assertEquals("http", endpoints.get(0).getProtocol());
    }

    /**
     * Tests that all registered endpoints are returned.
     */
    @Test
    public void testGetCoordinateDataAll() throws Exception {
        Resolver resolver = cn.getResolver();

        Resolver.CoordinateDataFilter filter = new Resolver.CoordinateDataFilter();
        Set<Endpoint> endpoints = resolver.getEndpoints(filter);
        assertEquals(4, endpoints.size());
    }

    /**
     * Tests that all methods of the filters are called and some basic filtering are functional.
     */
    @Test
    public void testGetCoordinateDataFilterOptions() throws Exception {
        Resolver resolver = cn.getResolver();
        final StringBuffer  filterCalls = new StringBuffer();

        Resolver.CoordinateDataFilter filter = new Resolver.CoordinateDataFilter() {
            @Override
            public boolean includeCell(final String datacenter) {;
                filterCalls.append(datacenter + ":");
                return true;
            }
            @Override
            public boolean  includeUser(final String user) {
                filterCalls.append(user + ":");
                return true;
            }
            @Override
            public boolean  includeService(final String service) {
                filterCalls.append(service + ":");
                return true;
            }
            @Override
            public boolean includeEndpointname(final String endpointName) {
                return endpointName.equals("foo");
            }
            @Override
            public boolean includeServiceState(final ServiceState state) {
                return state == ServiceState.RUNNING;
            }
        };
        Set<Endpoint> endpoints = resolver.getEndpoints(filter);
        assertEquals(1, endpoints.size());
        Endpoint selectedEndpoint = endpoints.iterator().next();
        
        assertEquals("foo", selectedEndpoint.getName());
        assertEquals("cell:user:service:", filterCalls.toString());
    }


    @Test
    public void testBasicAsyncResolving() throws Exception {
        Resolver resolver = cn.getResolver();
            
        final List<Endpoint> endpointListNew = new ArrayList<Endpoint>();
        final List<Endpoint> endpointListRemoved = new ArrayList<Endpoint>();
        final List<CountDownLatch> countDownLatches  = new ArrayList<CountDownLatch>();

        // This class is needed since the abstract resolver listener class can only access final variables.
        class LatchWrapper {
            public CountDownLatch latch;
        }
        final LatchWrapper latchWrapper = new LatchWrapper();

        latchWrapper.latch = new CountDownLatch(1);

        resolver.addResolverListener("foo.all.service.user.cell", new Resolver.ResolverListener() {

            @Override
            public void endpointEvent(Event event, Endpoint endpoint) {
                switch (event) {

                    case NEW_ENDPOINT:
                        System.err.println("Got new endpoint.");
                        endpointListNew.add(endpoint);
                        latchWrapper.latch.countDown();
                        break;
                    case REMOVED_ENDPOINT:
                        System.err.println("Removed endpoint.");
                        endpointListRemoved.add(endpoint);
                        latchWrapper.latch.countDown();
                        break;
                }
            }
        });
        assertTrue(latchWrapper.latch.await(5000, TimeUnit.MILLISECONDS));
        assertEquals(1, endpointListNew.size());
        assertEquals("foo", endpointListNew.get(0).getName());
        assertEquals("1.service.user.cell", endpointListNew.get(0).getCoordinate().toString());
        endpointListNew.clear();

        latchWrapper.latch = new CountDownLatch(1);

        undrain();

        assertTrue(latchWrapper.latch.await(5000, TimeUnit.MILLISECONDS));
        assertEquals(1, endpointListNew.size());

        assertEquals("foo", endpointListNew.get(0).getName());
        assertEquals("0.service.user.cell", endpointListNew.get(0).getCoordinate().toString());

        latchWrapper.latch = new CountDownLatch(2);
        endpointListNew.clear();

        changeEndpoint();

        assertTrue(latchWrapper.latch.await(5000, TimeUnit.MILLISECONDS));

        assertEquals(1, endpointListRemoved.size());

        assertEquals("0.service.user.cell", endpointListRemoved.get(0).getCoordinate().toString());
        assertEquals("foo", endpointListRemoved.get(0).getName());
        assertEquals("foo", endpointListNew.get(0).getName());
        assertEquals("0.service.user.cell", endpointListNew.get(0).getCoordinate().toString());
        assertEquals(4, endpointListNew.get(0).getPort());

        endpointListNew.clear();
        endpointListRemoved.clear();
        latchWrapper.latch = new CountDownLatch(1);

        drain();

        assertTrue(latchWrapper.latch.await(5000, TimeUnit.MILLISECONDS));

        assertEquals(1, endpointListRemoved.size());

        assertEquals("0.service.user.cell", endpointListRemoved.get(0).getCoordinate().toString());
        assertEquals("foo", endpointListRemoved.get(0).getName());
    }


    @Test(expected=IllegalArgumentException.class)
    public void testRegisterSameListenerTwice() throws Exception {
        Resolver resolver = cn.getResolver();
        Resolver.ResolverListener resolverListener = new Resolver.ResolverListener() {
            @Override
            public void endpointEvent(Event event, Endpoint endpoint) {

            }
        };
        resolver.addResolverListener("foo.all.service.user.cell", resolverListener);
        resolver.addResolverListener("bar.all.service.user.cell", resolverListener);
    }


    @Test
    public void testStopAsyncResolving() throws Exception {
        Resolver resolver = cn.getResolver();

        final List<Endpoint> endpointListNew = new ArrayList<Endpoint>();
        final List<Endpoint> endpointListRemoved = new ArrayList<Endpoint>();
        final List<CountDownLatch> countDownLatches  = new ArrayList<CountDownLatch>();

        // This class is needed since the abstract resolver listener class can only access final variables.
        class LatchWrapper {
            public CountDownLatch latch;
        }
        final LatchWrapper latchWrapper = new LatchWrapper();

        latchWrapper.latch = new CountDownLatch(1);

      
        Resolver.ResolverListener resolverListener = new Resolver.ResolverListener() {
            @Override
            public void endpointEvent(Event event, Endpoint endpoint) {
                switch (event) {

                    case NEW_ENDPOINT:
                        System.err.println("Got new endpoint.");
                        endpointListNew.add(endpoint);
                        latchWrapper.latch.countDown();
                        break;
                    case REMOVED_ENDPOINT:
                        System.err.println("Removed endpoint.");
                        endpointListRemoved.add(endpoint);
                        latchWrapper.latch.countDown();
                        break;
                }
            }
        };
        resolver.addResolverListener("foo.all.service.user.cell", resolverListener);
        assertTrue(latchWrapper.latch.await(5000, TimeUnit.MILLISECONDS));
        assertEquals(1, endpointListNew.size());
        assertEquals("foo", endpointListNew.get(0).getName());
        assertEquals("1.service.user.cell", endpointListNew.get(0).getCoordinate().toString());
        endpointListNew.clear();

        latchWrapper.latch = new CountDownLatch(1);

        resolver.removeResolverListener(resolverListener);
        
        undrain();

        assertFalse(latchWrapper.latch.await(100, TimeUnit.MILLISECONDS));

        try {
            resolver.removeResolverListener(resolverListener);
        } catch (IllegalArgumentException e) {
            // This is expected.
            return;
        }
        fail("Did not throw an exception on deleting a non existing listener.");
    }
    
    @Test
    public void testAnyResolving() throws Exception {
        Resolver resolver = cn.getResolver();
        List<Endpoint> endpoints = resolver.resolve("foo.any.service.user.cell");
        assertEquals(1, endpoints.size());
        assertEquals("foo", endpoints.get(0).getName());
        assertEquals("localhost", endpoints.get(0).getHost());
        assertEquals("1.service.user.cell", endpoints.get(0).getCoordinate().toString());
    }

    @Test
    public void testAllResolving() throws Exception {
        Resolver resolver = cn.getResolver();
        List<Endpoint> endpoints = resolver.resolve("all.service.user.cell");
        assertEquals(2, endpoints.size());
        assertEquals("foo", endpoints.get(0).getName());
        assertEquals("bar", endpoints.get(1).getName());
    }

    @Test
    public void testEndpointPatterns() throws Exception {
        // Test input that should match
        for (String s : validEndpointPatterns) {
            assertTrue("Didn't match '" + s + "'",
                       ZkResolver.endpointPattern.matcher(s).matches());
        }

        // Test input that should not match
        for (String s : validStrategyPatterns) {
            assertFalse("Matched '" + s + "'",
                        ZkResolver.endpointPattern.matcher(s).matches());
        }

        // Test input that should not match
        for (String s : validEndpointStrategyPatterns) {
            assertFalse("Matched '" + s + "'",
                        ZkResolver.endpointPattern.matcher(s).matches());
        }
    }

    @Test
    public void testStrategyPatterns() throws Exception {
        // Test input that should match
        for (String s : validStrategyPatterns) {
            assertTrue("Didn't match '" + s + "'",
                       ZkResolver.strategyPattern.matcher(s).matches());
        }

        // Test input that should not match
        for (String s : validEndpointPatterns) {
            assertFalse("Matched '" + s + "'",
                        ZkResolver.strategyPattern.matcher(s).matches());
        }
        // Test input that should not match
        for (String s : validEndpointStrategyPatterns) {
            assertFalse("Matched '" + s + "'",
                        ZkResolver.endpointPattern.matcher(s).matches());
        }
    }

    @Test
    public void testEndpointStrategyPatterns() throws Exception {
        // Test input that should match
        for (String s : validEndpointStrategyPatterns) {
            assertTrue("Didn't match '" + s + "'",
                       ZkResolver.endpointStrategyPattern.matcher(s).matches());
        }

        // Test input that should not match
        for (String s : validStrategyPatterns) {
            assertFalse("Matched '" + s + "'",
                        ZkResolver.endpointStrategyPattern.matcher(s).matches());
        }


        // Test input that should not match
        for (String s : validEndpointPatterns) {
            assertFalse("Matched '" + s + "'",
                        ZkResolver.endpointStrategyPattern.matcher(s).matches());
        }
    }
}