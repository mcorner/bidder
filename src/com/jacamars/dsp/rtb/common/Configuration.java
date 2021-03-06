package com.jacamars.dsp.rtb.common;

import java.io.BufferedReader;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;

import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.GetObjectTaggingRequest;
import com.amazonaws.services.s3.model.GetObjectTaggingResult;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.Tag;
import com.fasterxml.jackson.annotation.JsonIgnore;

import com.google.common.collect.Sets;
import com.jacamars.dsp.rtb.bidder.DeadmanSwitch;
import com.jacamars.dsp.rtb.bidder.RTBServer;
import com.jacamars.dsp.rtb.bidder.WebCampaign;
import com.jacamars.dsp.rtb.blocks.Bloom;
import com.jacamars.dsp.rtb.blocks.Cuckoo;
import com.jacamars.dsp.rtb.blocks.LookingGlass;
import com.jacamars.dsp.rtb.blocks.NavMap;
import com.jacamars.dsp.rtb.blocks.ProportionalEntry;
import com.jacamars.dsp.rtb.blocks.SimpleMultiset;
import com.jacamars.dsp.rtb.blocks.SimpleSet;
import com.jacamars.dsp.rtb.db.Database;
import com.jacamars.dsp.rtb.exchanges.adx.AdxGeoCodes;
import com.jacamars.dsp.rtb.exchanges.appnexus.Appnexus;
import com.jacamars.dsp.rtb.fraud.AnuraClient;
import com.jacamars.dsp.rtb.fraud.ForensiqClient;
import com.jacamars.dsp.rtb.fraud.FraudIF;
import com.jacamars.dsp.rtb.fraud.MMDBClient;
import com.jacamars.dsp.rtb.geo.GeoTag;
import com.jacamars.dsp.rtb.pojo.BidRequest;
import com.jacamars.dsp.rtb.rate.Limiter;
import com.jacamars.dsp.rtb.redisson.RedissonClient;
import com.jacamars.dsp.rtb.shared.FrequencyGoverner;
import com.jacamars.dsp.rtb.tools.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The singleton class that makes up the Configuration object. A configuration
 * is a JSON file that describes the campaigns and operational parameters needed
 * by the bidding engine.
 *
 * All classes needing config data retrieve it here.
 *
 * @author Ben M. Faul
 *
 */

public class Configuration {

	/** Keep a sleazy map of the campaigns around for quick lookup */
	static final Limiter handyMap = Limiter.getInstance();

	/** Log all requests */
	public static final int REQUEST_STRATEGY_ALL = 0;
	/** Log only requests with bids */
	public static final int REQUEST_STRATEGY_BIDS = 1;
	/** Log only requests with wins */
	public static final int REQUEST_STRATEGY_WINS = 2;

	/** Default index page */
	public static String indexPage = "/index.html";

	/** The singleton instance */
	static volatile Configuration theInstance;

	public static String ipAddress = null;

	public static int concurrency = 1;

	/** Geotag extension object */
	public GeoTag geoTagger = new GeoTag();
	/** The Nashhorn shell used by the bidder */
	JJS shell;
	/**
	 * The standard HTTP port the bidder uses, note this commands from the command
	 * line -p
	 */
	public int port = 8080;
	/** The standard HTTPS port the bidder runs on, if SSL is configured */
	public int sslPort = 8081;
	/** shard key for this bidder, comes from the command line -s */
	public String shard = "";
	/** The url of this bidder */
	public String url;
	/** The log level of the bidding engine */
	public int logLevel = 4;
	/** Set to true to see why the bid response was not bid on */
	public boolean printNoBidReason = false;
	/** The campaign watchdog timer */
	public long timeout = 80;
	/** The standard name of this instance */
	public static String instanceName = "default";
	/** The exchange seat ids used in bid responses */
	public static volatile Map<String, String> seats;
	/** the configuration item defining seats and their endpoints */
	public List<Map> seatsList;
	/** The blocking files */
	public List<Map> filesList;
	/** The 0MQ port used for freq cap */
	public volatile int swarmPort;

	/** The campaigns used to make bids */
	private volatile List<Campaign> campaignsList = new ArrayList<Campaign>();
	/** The list of exchanges that will be allowed, empty means all allowed */
	public volatile Set<String> overrideExchanges = null;
	/**
	 * If overrideExchanges are used, then these are the only campaigns allowed to
	 * bid regardless of what campaignsList says
	 */
	public volatile List<Campaign> overrideList = new ArrayList<Campaign>();

	/** An empty template for the exchange formatted message */
	public Map template = new HashMap();
	/** The vast url endpoing */
	public String vastUrl;
	/** The generalized (catch-all) event url */
	public String postbackUrl;
	/** Event track url */
	public String eventUrl;
	/** Standard pixel tracking URL */
	public String pixelTrackingUrl;
	/** Standard win URL */
	public String winUrl;
	/** The redirect URL */
	public String redirectUrl;
	/** The time to live in seconds for REDIS keys */
	public int ttl = 300;
	/** the list of initially loaded campaigns */
	public List<String> initialLoadlist;

	/** Macros found in the templates */
	public List<String> macros = new ArrayList<String>();
	/** The templates by by their exchange name */
	public Map<String, String> masterTemplate = new HashMap();
	/** Filename this originated from */
	public String fileName;
	/** The SSL Information, if SSL is supplied */
	public SSL ssl;
	/** The root password, passed in the Campaigns/payday.json file */
	public String password;

	// The Jedis pool, if it is used
	public MyJedisPool jedisPool;

	public static AmazonS3 s3;
	public static String s3_bucket;

	/**
	 * HTTP admin port, usually same as bidder, but set this for a different port
	 * for admin functions
	 */
	public int adminPort = 0;
	/** Tell whether the port is supposed to be SSL or not, default is not */
	public boolean adminSSL = false;

	/** Test bid request for fraud */
	public static FraudIF forensiq;

	/** The master CIDR list */
	public static volatile NavMap masterCidr = null;

	/**
	 * ZEROMQ LOGGING INFO
	 *
	 */
	/** The channel that handles video channels */
	public volatile String VIDEOEVENTS_CHANNEL = null;
	/** THe channel that handles generic events */
	public volatile String POSTBACKEVENTS_CHANNEL = null;
	/** The channel that raw requests are written to */
	public volatile String BIDS_CHANNEL = null;
	/** The channel that wins are written to */
	public volatile String WINS_CHANNEL = null;
	/** The channel the bid requests are written to */
	public volatile String REQUEST_CHANNEL = null;
	/** The channel the bid requests are written to for unilogger */
	public volatile String UNILOGGER_CHANNEL = null;
	/** The channel clicks are written to */
	public volatile String CLICKS_CHANNEL = null;
	/** The channel nobids are written to */
	public volatile String NOBIDS_CHANNEL = null;
	/** The channel to output forensiq data */
	public volatile String FORENSIQ_CHANNEL = null;
	/** The channel to send status messages */
	public volatile String PERF_CHANNEL = null;
	/** The channel to send metassp messages */
	public volatile String MSSP_CHANNEL = null;
	/** The channel trasnmitting pixels */
	public volatile String PIXELS_CHANNEL = null;
	/** The channel the bidder sends command responses out on */
	public volatile static String RESPONSES_SEND = null;
	/** The channel the bidder receives responses for commands on */
	public volatile static String RESPONSES_RECEIVE = null;

	// Channel that reports reasons
	public volatile static String REASONS_CHANNEL = null;
	/** Zeromq command port */
	public volatile static String commandsPort;
	/** Whether to allow multiple bids per response */
	public volatile static boolean multibid = false;

	/** Configuration defined macro definitions */
	transient volatile Map<String, String> systemMacros = new HashMap();

	/** Logging strategy for logs */
	public volatile static int requstLogStrategy = REQUEST_STRATEGY_ALL;

	/** Zookeeper instance */
	public volatile static ZkConnect zk;

	public volatile List<String> commandAddresses = new ArrayList<String>();

	public static final int STRATEGY_HEURISTIC = 0;
	public static final int STRATEGY_MAX_CONNECTIONS = 1;

	/** The host name where the aerospike lives */
	public volatile String cacheHost = null;
	/** The aerospike TCP port */
	public volatile int cachePort = 3000;
	/** Max number of aerospike connections */
	public volatile int maxconns = 300;

	public volatile String udfModule = null;
	public volatile boolean forceRegisterUdfModule = false;

	/** Pause on Startup */
	public volatile boolean pauseOnStart = false;
	/** a copy of the config verbosity object */
	public volatile Map verbosity;
	/** A copy of the the geotags config */
	public Map geotags;
	/** Deadman switch */
	public volatile DeadmanSwitch deadmanSwitch;
	String deadmanKey = null;

	/** Set the throttle */
	public volatile long throttle = 100;

	/** Logging object */
	static final Logger logger = LoggerFactory.getLogger(Configuration.class);

	///////////////////////////////////////////////////////////////////////
	//
	// NASHHORN BASED CORRECTIONS FROM THE TEMPLATE FOR SMAATO
	//
	// These are read by the SmaatoBidResponse, and are set
	// when the campaign is created
	//
	///////////////////////////////////////////////////////////////////////
	/**
	 * These are filled in from the templates
	 */
	@JsonIgnore
	transient public String SMAATOclickurl = "";
	@JsonIgnore
	transient public String SMAATOimageurl = "";
	@JsonIgnore
	transient public String SMAATOtooltip = "";
	@JsonIgnore
	transient public String SMAATOadditionaltext = "";
	@JsonIgnore
	transient public String SMAATOpixelurl = "";
	@JsonIgnore
	transient public String SMAATOtext = "";
	@JsonIgnore
	transient public String SMAATOscript = "";

	public RedissonClient redisson;

	/** My Ip Address as known by the outside world */
	static volatile String myIpAddress = null;

	/** 0MQ channel we receive commands from */
	public static String COMMANDS = null;

	/**
	 * Private constructor, class has no public constructor.
	 */
	private Configuration() throws Exception {

	}

	public static void reset() {
		theInstance = null;
	}

	/**
	 * Clear the config entries to default state,
	 */
	public void clear() {
		shard = "";
		port = 8080;
		url = null;
		logLevel = 4;
		campaignsList.clear();
		overrideList.clear();
	}

	public void initialize(String fileName) throws Exception {
		this.fileName = fileName;
		initialize(fileName, "", 8080, 8081, null);
	}

	/**
	 * Initialize the system from the JSON or Aerospike configuration file.
	 *
	 * @param path      String - The file name containing the Java Bean Shell code.
	 * @param shard     Strimg. The shard name
	 * @param port      int. The port the web access listens on
	 * @param sslPort   int. The port the SSL listens on.
	 * @param exchanges String. The comma separated list of exchanges
	 * @throws Exception on file errors.
	 */
	public void initialize(String path, String shard, int port, int sslPort, String exchanges) throws Exception {
		this.fileName = path;

		/**
		 * Override the exchanges in payday.json. This means any campaign that does not
		 * specifically have a rule using "exchange" will be allowed, but any campaign
		 * that has a rule with "exchange" that does not match the list will be marked
		 * INACTIVE
		 */
		Map<String, String> env = System.getenv();
		if (env.get("EXCHANGES") != null || exchanges != null) {
			String str = env.get("EXCHANGES");
			if (exchanges != null)
				str = exchanges;
			String[] parts = str.split(",");
			overrideExchanges = Sets.newHashSet(parts);
			logger.warn("*** Exchanges configured in config file are restricted by EXCHANGES environment to this: {}",
					overrideExchanges);
		}

		/******************************
		 * System Name
		 *****************************/
		this.shard = shard;
		this.port = port;
		this.sslPort = sslPort;

		java.net.InetAddress localMachine = null;
		String useName = null;
		try {
			localMachine = java.net.InetAddress.getLocalHost();
			ipAddress = localMachine.getHostAddress();
			useName = localMachine.getHostName();
		} catch (Exception error) {
			useName = getIpAddress();
		}

		if (shard == null || shard.length() == 0)
			instanceName = useName;
		else
			instanceName = shard + ":" + useName;

		/**
		 * Set up temp files
		 */
		Files.createDirectories(Paths.get("www/temp")); // create the temp
														// directory in www so
														// preview campaign will
														// work

		/*********************************************
		 * USE ZOOKEEPER, AEROSPIKE OR FILE CONFIG
		 *********************************************/
		String str = null;
		byte[] encoded = Files.readAllBytes(Paths.get(path));
		str = Charset.defaultCharset().decode(ByteBuffer.wrap(encoded)).toString();

		str = Configuration.substitute(str);
//		System.out.println("AFTER SUBSTITUTIONS, CONFIGURATION:\n" + str);

		Map<?, ?> m = DbTools.mapper.readValue(str, Map.class);
		/*******************************************************************************/
		//////////////////////////////////////////////////////////////

		seats = new HashMap<String, String>();
		if (m.get("lists") != null) {
			filesList = (List) m.get("lists");
			initializeLookingGlass(filesList);
		}

		if (m.get("s3") != null) {
			Map<String, Object> ms3 = (Map) m.get("s3");
			String accessKey = (String) ms3.get("access_key_id");
			String secretAccessKey = (String) ms3.get("secret_access_key");
			String region = (String) ms3.get("region");
			s3_bucket = (String) ms3.get("bucket");

			if (!(accessKey.length() == 0 || secretAccessKey.length() == 0 || region.length() == 0)) {

				ClientConfiguration cf = new ClientConfiguration();

				if (ms3.get("proxyhost") != null) {
					String proxyhost = (String) ms3.get("proxyhost");
					int proxyport = (Integer) ms3.get("proxyport");
					logger.info("S3 Using host: {}, port: {}", proxyhost, proxyport);
					String proto = (String) ms3.get("proxyprotocol");
					cf.setProxyHost(proxyhost);
					cf.setProxyPort(proxyport);
					if (proto != null) {
						if (proto.equalsIgnoreCase("http"))
							cf.setProtocol(Protocol.HTTP);
						else
							cf.setProtocol(Protocol.HTTPS);
					}
				}

				BasicAWSCredentials creds = new BasicAWSCredentials(accessKey, secretAccessKey);
				s3 = AmazonS3ClientBuilder.standard().withClientConfiguration(cf)
						.withCredentials(new AWSStaticCredentialsProvider(creds)).withRegion(Regions.fromName(region))
						.build();

				ObjectListing listing = s3.listObjects(new ListObjectsRequest().withBucketName(s3_bucket));

				/**
				 * Lazy Load
				 */
				Runnable task = () -> {
					try {
						processDirectory(s3, listing, s3_bucket);
					} catch (Exception error) {
						System.err.println("ERROR IN AWS LISTING: " + error.toString());
					}
				};
				Thread thread = new Thread(task);
				thread.start();
			} else {
				logger.info("S3 is not configured");
			}
		}

		/**
		 * Check for @MASTERCIDR after the files are loaded, or, duh, it's not there
		 * yet.
		 */
		if (LookingGlass.symbols.get("@MASTERCIDR") != null) {
			Object x = LookingGlass.symbols.get("@MASTERCIDR");
			if (x != null) {
				if (x instanceof NavMap) {
					masterCidr = (NavMap) x;
					logger.info("*** Master Blacklist is set to: {}", x);
				} else {
					logger.error("*** Master CIDR '@MASTERCIDR' is  the wrong classtype {}", x.getClass().getName());
					logger.error("*** Master CIDR blocking is disabled ***");
				}
			}
		} else
			logger.info("*** Master Blacklist is not set");
		/**
		 * SSL
		 */
		if (m.get("ssl") != null) {
			Map x = (Map) m.get("ssl");
			ssl = new SSL();
			ssl.setKeyManagerPassword = (String) x.get("setKeyManagerPassword");
			ssl.setKeyStorePassword = (String) x.get("setKeyStorePassword");
			ssl.setKeyStorePath = (String) x.get("setKeyStorePath");
		}
		/**
		 * Create the seats id map, and create the bin and win handler classes for each
		 * exchange
		 */
		seatsList = (List<Map>) m.get("seats");
		for (int i = 0; i < seatsList.size(); i++) {
			Map x = seatsList.get(i);

			instanceBidRequest(x);
		}

		/**
		 * Set GDPR mode
		 */
		String gdpr = (String) m.get("GDPR_MODE");
		if (gdpr != null && gdpr.length() > 0) {
			RTBServer.GDPR_MODE = Boolean.parseBoolean(gdpr);
		}

		/**
		 * Create forensiq, anura or organizational trap in mmdb
		 */
		Map<String, String> fraud = (Map) m.get("fraud");
		if (fraud != null && !fraud.get("type").equals("")) {
			if (fraud.get("type").equalsIgnoreCase("Forensiq")) {
				logger.info("*** Fraud detection is set to Forensiq");
				ForensiqClient.key = fraud.get("ck");
				if (!fraud.get("threshhold").equals(""))
					ForensiqClient.threshhold = Integer.parseInt(fraud.get("threshhold"));

				if (!fraud.get("endpoint").equals(""))
					ForensiqClient.endpoint = fraud.get("endpoint");

				if (!fraud.get("bidOnError").equals(""))
					ForensiqClient.bidOnError = Boolean.parseBoolean(fraud.get("bidOnError"));
				if (!fraud.get("connections").equals(""))
					ForensiqClient.getInstance().connections = Integer.parseInt(fraud.get("connections"));

				forensiq = ForensiqClient.build();
			} else if (fraud.get("type").equalsIgnoreCase("Anura")) {
				logger.info("*** Fraud detection is set to Anura");
				AnuraClient.key = fraud.get("ck");
				if (!fraud.get("threshhold").equals(""))
					AnuraClient.threshhold = Integer.parseInt(fraud.get("threshhold"));

				if (!fraud.get("endpoint").equals(""))
					AnuraClient.endpoint = fraud.get("endpoint");

				if (!fraud.get("bidOnError").equals(""))
					AnuraClient.bidOnError = Boolean.parseBoolean(fraud.get("bidOnError"));

				if (!fraud.get("connections").equals(""))
					AnuraClient.getInstance().connections = Integer.parseInt(fraud.get("connections"));

				forensiq = AnuraClient.build();
			} else if (fraud.get("type").equalsIgnoreCase("MMDB")) {
				logger.info("*** Fraud detection is set to MMDB");
				String db = (String) fraud.get("endpoint");
				if (db == null) {
					throw new Exception("No fraud db specified for MMDB");
				}
				MMDBClient fy;
				try {
					fy = MMDBClient.build(db);
				} catch (Error error) {
					throw error;
				}
				if (!fraud.get("bidOnError").equals("")) {
					fy.bidOnError = Boolean.parseBoolean(fraud.get("bidOnError"));
				}
				if (!fraud.get("watchlist").equals("")) {
					fy.setWatchlist(fraud.get("watchlist"));
				}
				forensiq = fy;
			}
		} else {
			logger.info("*** NO Fraud detection");
		}

		/**
		 * Deal with the app object
		 */
		m = (Map) m.get("app");

		if (m.get("geopatch") != null) {
			String fileName = (String) m.get("geopatch");
			if (!fileName.equals("")) {
				GeoPatch.getInstance(fileName);
				logger.info("*** GEOPATCH DB set to: {} ", fileName);
			} else
				logger.info("*** GEOPATCH DB IS NOT SET");
		} else
			logger.info("*** GEOPATCH DB IS NOT SET");

		if (m.get("indexPage") != null)
			indexPage = (String) m.get("indexPage");

		if (m.get("throttle") != null) {
			String key = (String) m.get("throttle");
			throttle = Long.parseLong(key);
		}

		if (m.get("deadmanswitch") != null) {
			deadmanKey = (String) m.get("deadmanswitch");
			if (deadmanKey.equalsIgnoreCase("NONE"))
				deadmanKey = null;
		}

		if (m.get("trace") != null) {
			String strace = (String) m.get("trace");
			if (strace.equalsIgnoreCase("true"))
				RTBServer.trace = true;
			else
				RTBServer.trace = false;

		}

		if (m.get("concurrency") != null) {
			String mstr = (String) m.get("concurrency");
			concurrency = Integer.parseInt(mstr);
		}

		if (m.get("systemMacros") != null) {
			systemMacros = (Map<String, String>) m.get("systemMacros");

			for (String name : systemMacros.keySet()) {
				String what = systemMacros.get(name);
				what = substitute(what);
				systemMacros.put(name, what);
				MacroProcessing.addMacro(name);
			}
		}

		password = (String) m.get("password");

		if (m.get("threads") != null) {
			String mstr = (String) m.get("threads");
			RTBServer.threads = Integer.parseInt(mstr);
		}

		if (m.get("adminPort") != null) {
			String mstr = (String) m.get("adminPort");
			adminPort = (Integer) Integer.parseInt(mstr);
		}
		if (m.get("adminSSL") != null) {
			adminSSL = (Boolean) m.get("adminSSL");
		}

		String strategy = (String) m.get("strategy");
		if (strategy != null && strategy.equals("heuristic"))
			RTBServer.strategy = STRATEGY_HEURISTIC;
		else
			RTBServer.strategy = STRATEGY_MAX_CONNECTIONS;

		if (m.get("nobid-reason") != null)
			printNoBidReason = Boolean.parseBoolean((String) verbosity.get("nobid-reason"));

		template = (Map) m.get("template");
		if (template == null) {
			throw new Exception("No template defined");
		}
		encodeTemplates();
		encodeTemplateStubs();

		geotags = (Map) m.get("geotags");
		if (geotags != null) {
			String states = (String) geotags.get("states");
			String codes = (String) geotags.get("zipcodes");
			geoTagger.initTags(states, codes);
		}

		Boolean bValue = false;
		bValue = (Boolean) m.get("stopped");
		if (bValue != null && bValue == true) {
			RTBServer.stopped = true;
			pauseOnStart = true;
		}

		Map redis = (Map) m.get("redis");
		if (redis != null) {
			Integer rsize = (Integer) redis.get("pool");
			if (rsize == null)
				rsize = 64;

			String host = (String) redis.get("host");
			Integer rport = (Integer) redis.get("port");
			if (rport == null)
				rport = 6379;

			MyJedisPool.host = host;
			MyJedisPool.port = rport;
			jedisPool = new MyJedisPool(1000, 1000, 5);

			logger.info("*** JEDISPOOL = {}/{}/{} {}", jedisPool, host, rport, rsize);
		}

		Map zeromq = (Map) m.get("zeromq");
		if (zeromq == null) {
			throw new Exception("Zeromq is mot configured!");
		}

		String value = null;
		Double dValue = 0.0;
		bValue = false;

		/**
		 * Zeromq
		 */
		if ((value = (String) zeromq.get("videoevents")) != null)
			VIDEOEVENTS_CHANNEL = value;
		if ((value = (String) zeromq.get("postbackevents")) != null)
			POSTBACKEVENTS_CHANNEL = value;
		if ((value = (String) zeromq.get("bidchannel")) != null)
			BIDS_CHANNEL = value;
		if ((value = (String) zeromq.get("nobidchannel")) != null)
			NOBIDS_CHANNEL = value;
		if ((value = (String) zeromq.get("winchannel")) != null)
			WINS_CHANNEL = value;
		if ((value = (String) zeromq.get("requests")) != null)
			REQUEST_CHANNEL = value;
		if ((value = (String) zeromq.get("unilogger")) != null)
			UNILOGGER_CHANNEL = value;
		if ((value = (String) zeromq.get("clicks")) != null)
			CLICKS_CHANNEL = value;
		if ((value = (String) zeromq.get("pixels")) != null)
			PIXELS_CHANNEL = value;
		if ((value = (String) zeromq.get("fraud")) != null)
			FORENSIQ_CHANNEL = value;
		COMMANDS = (String) zeromq.get("commands");
		RESPONSES_SEND = (String) zeromq.get("responses");
		String ls = (String) zeromq.get("xfrport");
		ls = substitute(ls);
		int listen = Integer.parseInt(ls);
		String host = getHostFrom(RESPONSES_SEND);
		int pub = getPortFrom(RESPONSES_SEND);
		int sub = getPortFrom(COMMANDS);

		String test = (String) zeromq.get("frequencygoverner");
		if (test != null && test.equals("true"))
			FrequencyGoverner.silent = false;
		else
			FrequencyGoverner.silent = true;
		RTBServer.frequencyGoverner = new FrequencyGoverner(host, sub, pub, 900);

		redisson = new RedissonClient();
		redisson.setSharedObject(host, listen);

		Database.getInstance(redisson);

		if ((value = (String) zeromq.get("status")) != null)
			PERF_CHANNEL = value;
		if ((value = (String) zeromq.get("metasspUnified")) != null)
			MSSP_CHANNEL = value;
		if ((value = (String) zeromq.get("reasons")) != null)
			REASONS_CHANNEL = value;

		/////////////////////////////////////////////////////////////////////

		if (zeromq.get("requeststrategy") != null) {
			strategy = (String) zeromq.get("requeststrategy");
			if (strategy.equalsIgnoreCase("all") || strategy.equalsIgnoreCase("requests"))
				requstLogStrategy = REQUEST_STRATEGY_ALL;
			else if (strategy.equalsIgnoreCase("bids"))
				requstLogStrategy = REQUEST_STRATEGY_BIDS;
			else if (strategy.equalsIgnoreCase("WINS"))
				requstLogStrategy = REQUEST_STRATEGY_WINS;
			else {
				if (strategy.contains(".") == false) {
					int n = Integer.parseInt(strategy);
					ExchangeLogLevel.getInstance().setStdLevel(n);
				} else {
					Double perc = Double.parseDouble(strategy);
					ExchangeLogLevel.getInstance().setStdLevel(perc.intValue());
				}
			}
		}
		/********************************************************************/

		if (deadmanKey != null) {
			deadmanSwitch = new DeadmanSwitch(redisson, deadmanKey);
			deadmanSwitch.start();
		}

		campaignsList.clear();
		overrideList.clear();

		vastUrl = (String) m.get("vasturl");
		if (vastUrl == null) {
			vastUrl = "http://localhost:8080/vast";
			logger.error("No vasturl is set, it will be set to localhost, which will NOT work in production");
		}

		postbackUrl = (String) m.get("postbackurl");
		if (postbackUrl == null) {
			postbackUrl = "http://localhost:8080/postback";
			logger.error("No postback is set, it will be set to localhost, which will NOT work in production");
		}

		eventUrl = (String) m.get("eventurl");
		if (eventUrl == null) {
			eventUrl = "http://localhost:8080/track";
			logger.error("No eventurl is set, it will be set to localhost, which will NOT work in production");
		}

		pixelTrackingUrl = (String) m.get("pixel-tracking-url");
		winUrl = (String) m.get("winurl");
		redirectUrl = (String) m.get("redirect-url");
		if (m.get("ttl") != null) {
			ttl = (Integer) m.get("ttl");
		}

		if (m.get("demodb") != null) {
			String demodb = (String) m.get("demodb");
			if (demodb.length() > 0) {
				initialLoadlist = readDatabaseIntoCache(demodb);
				for (String camp : initialLoadlist) {
					fastAddCampaign(camp);
				}
			}
		}

		recompile();

		if (winUrl.contains("localhost")) {
			logger.warn("*** WIN URL IS SET TO LOCALHOST, NO REMOTE ACCESS WILL WORK FOR WINS ***");
		}

		printEnvironment();
	}

	void printEnvironment() throws Exception {

		String[] args = new String[] { "FREQGOV", "HOSTNAME", "BROKERLIST", "PUBSUB", "WIN", "PIXEL", "VIDEO", "BID",
				"EXTERNAL", "PUBPORT", "SUBPORT", "INITPORT", "TRACE", "THREADS", "CONCURRENCY", "ADMINPORT",
				"REQUESTSTRATEGY", "ACCOUNTING", "THROTTLE", "IPADRESS", "TRACKER", "BROKERLIST", "NOBIDREASON" };

		String[] macros = { "pixel_tracker", "redirect_tracker", "postback_tracker", "event_tracker",
				"pixel-tracking-url", "winurl", "redirect-url", "vasturl", "eventurl", "postbackurl" };

		Map<String, String> env = System.getenv();
		logger.info("************* ENVIROMENT VARIABLES SET ******************");
		for (String envName : env.keySet()) {
			logger.info("ENVIRONMENT: " + envName + "\t" + env.get(envName));
		}
		logger.info("*********************************************************");

		logger.info("***************** SUBSTITUTIONS **************************");
		for (String s : args) {
			String addr = s + ":\t$" + s;
			String address = substitute(addr);
			logger.info(address);
		}
		logger.info("*********************************************************");

		logger.info("******************* MACRO TESTS **************************");

		logger.info("{redirect_url} = " + redirectUrl);
		logger.info("{event_url} = " + eventUrl);
		logger.info("{vast_url} = " + vastUrl);
		logger.info("{postback_url} = " + postbackUrl);
		logger.info("{win_url} = " + winUrl);

		logger.info("**********************************************************");
		logger.info("GDPR Mode = " + RTBServer.GDPR_MODE);

	}

	public static String getHostFrom(String address) {
		String s = address.replaceAll("tcp://", "");
		int i = s.indexOf(":");
		if (i >= 0)
			return s.substring(0, i);
		else
			return s;
	}

	public static int getPortFrom(String address) {
		address = address.replaceAll("tcp://", "");
		int i = address.indexOf(":");
		if (i < 0)
			return -1;
		address = address.substring(i + 1);
		i = address.indexOf("&");
		if (i > 0)
			address = address.substring(0, i);
		return Integer.parseInt(address);
	}

	/**
	 * Substitute the macros and environment variables found in the the string.
	 *
	 * @param address String. The address being queries/
	 * @return String. All found environment vars will be substituted.
	 * @throws Exception on parsing errors.
	 */
	public static String substitute(String address) throws Exception {

		if (address == null)
			return address;

		///////////////////////////////////////////////////////////////////////////////////////////////////

		while (address.contains("$BIDSCHANNEL"))
			address = GetEnvironmentVariable(address, "$BIDSCHANNEL", "kafka://[$BROKERLIST]&topic=bids");
		while (address.contains("$WINSCHANNEL"))
			address = GetEnvironmentVariable(address, "$WINSCHANNEL", "kafka://[$BROKERLIST]&topic=wins");
		while (address.contains("$REQUESTSCHANNEL"))
			address = GetEnvironmentVariable(address, "$REQUESTSCHANNEL", "kafka://[$BROKERLIST]&topic=requests");
		while (address.contains("$CLICKSCHANNEL"))
			address = GetEnvironmentVariable(address, "$CLICKSCHANNEL", "kafka://[$BROKERLIST]&topic=clicks");
		while (address.contains("$PIXELSCHANNEL"))
			address = GetEnvironmentVariable(address, "$PIXELSCHANNEL", "kafka://[$BROKERLIST]&topic=pixels");
		while (address.contains("$VIDEOEVENTSCHANNEL"))
			address = GetEnvironmentVariable(address, "$VIDEOEVENTSCHANNEL", "kafka://[$BROKERLIST]&topic=videoevents");
		while (address.contains("$POSTBACKEVENTSCHANNEL"))
			address = GetEnvironmentVariable(address, "$POSTBACKEVENTSCHANNEL",
					"kafka://[$BROKERLIST]&topic=postbackevents");
		while (address.contains("$STATUSCHANNEL"))
			address = GetEnvironmentVariable(address, "$STATUSCHANNEL", "kafka://[$BROKERLIST]&topic=status");
		while (address.contains("$REASONSCHANNEL"))
			address = GetEnvironmentVariable(address, "$REASONSCHANNEL", "kafka://[$BROKERLIST]&topic=reasons");
		while (address.contains("$LOGCHANNEL"))
			address = GetEnvironmentVariable(address, "$LOGCHANNEL", "kafka://[$BROKERLIST]&topic=logs");

		//////////////////////////////////////////////////////////////////////////////////////////////////////////

		while (address.contains("$GEOPATCH"))
			address = GetEnvironmentVariable(address, "$GEOPATCH", "");

		while (address.contains("$MASTERCIDR"))
			address = GetEnvironmentVariable(address, "$MASTERCIDR", "");

		while (address.contains("$FRAUDTYPE"))
			address = GetEnvironmentVariable(address, "$FRAUDTYPE", "");
		while (address.contains("$FRAUDTHRESHOLD"))
			address = GetEnvironmentVariable(address, "$FRAUDTHRESHOLD", "100");
		while (address.contains("$FRAUDKEY"))
			address = GetEnvironmentVariable(address, "$FRAUDKEY", "");
		while (address.contains("$FRAUDENDPOINT"))
			address = GetEnvironmentVariable(address, "$FRAUDENDPOINT", "");
		while (address.contains("$FRAUDCONNECTIONS"))
			address = GetEnvironmentVariable(address, "$FRAUDCONNECTIONS", "100");
		while (address.contains("$FRAUDWATCHLIST"))
			address = GetEnvironmentVariable(address, "$FRAUDWATCHLIST", "");

		while (address.contains("$BIDSWITCH_ID"))
			address = GetEnvironmentVariable(address, "$BIDSWITCH_ID", "bidswitch-id");

		/////////////////////////////////////////////////////////////////////////////

		while (address.contains("$S3BUCKET"))
			address = GetEnvironmentVariable(address, "$S3BUCKET", "");

		while (address.contains("$AWSACCESSKEY"))
			address = GetEnvironmentVariable(address, "$AWSACCESSKEY", "");

		while (address.contains("$AWSSECRETKEY"))
			address = GetEnvironmentVariable(address, "$AWSSECRETKEY", "");

		while (address.contains("$AWSREGION"))
			address = GetEnvironmentVariable(address, "$AWSREGION", Regions.US_EAST_1.getName());

		while (address.contains("$AWSKINESIS_STREAM"))
			address = GetEnvironmentVariable(address, "$AWS_KINESIS_STREAM", "");

		while (address.contains("$AWSKINESIS_PARTITION"))
			address = GetEnvironmentVariable(address, "$AWS_KINESESIS_PARTITION", "part-1");

		while (address.contains("AWSKINESIS_SHARD"))
			address = GetEnvironmentVariable(address, "$AWS_KINESIS_SHARD", "shardId-000000000000");

		while (address.contains("AWS_KINESIS_ITERATOR"))
			address = GetEnvironmentVariable(address, "$AWS_KENESIS_ITERATOR", "LATEST");

		while (address.contains("AWSKINESIS_RECORDS"))
			address = GetEnvironmentVariable(address, "$AWS_KENSIS_RECORDS", "1");

		/////////////////////////////////////////////////////////////////////////////

		while (address.contains("$FREQGOV"))
			address = GetEnvironmentVariable(address, "$FREQGOV", "true");

		while (address.contains("$GDPR_MODE"))
			address = GetEnvironmentVariable(address, "$GDPR_MODE", "false");

		while (address.contains("$HOSTNAME"))
			address = GetEnvironmentVariable(address, "$HOSTNAME", Configuration.instanceName);
		while (address.contains("$BROKERLIST"))
			address = GetEnvironmentVariable(address, "$BROKERLIST", "localhost:9092");
		while (address.contains("$PUBSUB"))
			address = GetEnvironmentVariable(address, "$PUBSUB", "localhost");

		while (address.contains("$WIN"))
			address = GetEnvironmentVariable(address, "$WIN", "localhost");
		while (address.contains("$PIXEL"))
			address = GetEnvironmentVariable(address, "$PIXEL", "localhost");
		while (address.contains("$VIDEO"))
			address = GetEnvironmentVariable(address, "$VIDEO", "localhost");
		while (address.contains("$BID"))
			address = GetEnvironmentVariable(address, "$BID", "localhost");
		while (address.contains("$EXTERNAL"))
			address = GetEnvironmentVariable(address, "$EXTERNAL", "http://localhost:8080");

		while (address.contains("$PUBPORT"))
			address = GetEnvironmentVariable(address, "$PUBPORT", "6000");
		while (address.contains("$SUBPORT"))
			address = GetEnvironmentVariable(address, "$SUBPORT", "6001");
		while (address.contains("$INITPORT"))
			address = GetEnvironmentVariable(address, "$INITPORT", "6002");
		while (address.contains("$THREADS"))
			address = GetEnvironmentVariable(address, "$THREADS", "2000");
		while (address.contains("$CONCURRENCY"))
			address = GetEnvironmentVariable(address, "$CONCURRENCY", "3");
		while (address.contains("$ADMINPORT"))
			address = GetEnvironmentVariable(address, "$ADMINPORT", "8155");
		while (address.contains("$REQUESTSTRATEGY"))
			address = GetEnvironmentVariable(address, "$REQUESTSTRATEGY", "100");
		while (address.contains("$ACCOUNTING"))
			address = GetEnvironmentVariable(address, "$ACCOUNTING", "accountingsystem");
		while (address.contains("$INDEXPAGE"))
			address = GetEnvironmentVariable(address, "$INDEXPAGE", "/index.html");
		while (address.contains("$THROTTLE"))
			address = GetEnvironmentVariable(address, "$THROTTLE", "100");
		while (address.contains("$NOBIDREASON"))
			address = GetEnvironmentVariable(address, "$NOBIDREASON", "false");

		while (address.contains("$IPADDRESS"))
			address = GetIpAddressFromInterface(address);

		while (address.contains("$TRACKER"))
			address = GetEnvironmentVariable(address, "$TRACKER", "localhost:8080");

		while (address.contains("$ADX_EKEY"))
			address = GetEnvironmentVariable(address, "$ADX_EKEY", "");
		while (address.contains("$ADX_IKEY"))
			address = GetEnvironmentVariable(address, "$ADX_IKEY", "");
		while (address.contains("$OPENX_EKEY"))
			address = GetEnvironmentVariable(address, "$OPENX_EKEY", "");
		while (address.contains("$OPENX_IKEY"))
			address = GetEnvironmentVariable(address, "$OPENX_IKEY", "");
		while (address.contains("$GOOGLE_EKEY"))
			address = GetEnvironmentVariable(address, "$GOOGLE_EKEY", "");
		while (address.contains("$GOOGLE_IKEY"))
			address = GetEnvironmentVariable(address, "$GOOGLE_IKEY", "");

		while (address.contains("$DEMODB"))
			address = GetEnvironmentVariable(address, "$DEMODB", "");

		address = GetEnvironmentVariable(address, "$TRACE", "false");

		return address;
	}

	/**
	 * Retrieve a variable from the environment variables
	 *
	 * @param address String. The address string to change.
	 * @param varName String. The name of the environment variable, begins with $
	 * @return String. The address string modified.
	 */
	public static String GetEnvironmentVariable(String address, String varName) {
		if (address.contains(varName)) {
			String sub = varName.substring(1);
			Map<String, String> env = System.getenv();
			if (env.get(sub) != null) {
				address = address.replace(varName, env.get(sub));
				return address;
			}
			return null;
		}
		return address;
	}

	/**
	 * Retrieve a variable from the environment variables, and if it exists, use
	 * that, else use the alternate.
	 *
	 * @param address String. The address string to change.
	 * @param varName String. The name of the environment variable, begins with $
	 * @param altName String. The name to use if the environment variables is not
	 *                defined.
	 * @return String. The address string modified.
	 */
	public static String GetEnvironmentVariable(String address, String varName, String altName) {
		String test = GetEnvironmentVariable(address, varName);
		if (test == null && altName != null) {
			test = address.replace(varName, altName);
		}
		return test;
	}

	/**
	 * Get the first IP address from a specified interface, in the form
	 * $IPADRESS#IFACE-NAME#
	 *
	 * @param address String. The address we are looking at
	 * @return String. The first occurrance of $IPADDRESS#XXX# will be substituted,
	 *         if found
	 * @throws Exception on parsing errors.
	 */
	public static String GetIpAddressFromInterface(String address) throws Exception {
		int i = address.indexOf("$IPADDRESS");
		if (i < 0)
			return address;

		if (address.charAt(i + 10) == '#') {
			String chunk = address.substring(i + 12);
			int j = chunk.indexOf("#");
			if (j < 0)
				address = address.replace("$IPADDRESS", Performance.getInternalAddress());
			else {
				String key = address.substring(i, i + 13 + j);
				String[] parts = key.split("#");
				address = address.replace(key, Performance.getInternalAddress(parts[1]));
			}
		} else {
			address = address.replace("$IPADDRESS", Performance.getInternalAddress());
		}
		return address;
	}

	/**
	 * Return macros defined in the configuration file
	 *
	 * @param macro String. The name of the macro.
	 * @return String. The returned value.
	 */
	public String getMacroDefinition(String macro) {
		return systemMacros.get(macro);
	}

	/**
	 * Return the bid request log strategy as a string
	 *
	 * @return String. The strategy we are currently using.
	 */
	public String requstLogStrategyAsString() {
		switch (requstLogStrategy) {
		case REQUEST_STRATEGY_ALL:
			return "all";
		case REQUEST_STRATEGY_BIDS:
			return "bids";
		case REQUEST_STRATEGY_WINS:
			return "wins";
		default:
		}
		return "all";
	}

	public void processDirectory(AmazonS3 s3, ObjectListing listing, String bucket) throws Exception {

		double time = System.currentTimeMillis();
		ExecutorService executor = Executors.newFixedThreadPool(16);

		int count = 0;

		for (S3ObjectSummary objectSummary : listing.getObjectSummaries()) {
			if ("STANDARD".equalsIgnoreCase(objectSummary.getStorageClass())) {
				long size = objectSummary.getSize();
				logger.debug("*** Processing S3 {}, size: {}", objectSummary.getKey(), size);
				S3Object object = s3.getObject(new GetObjectRequest(bucket, objectSummary.getKey()));

				String bucketName = object.getBucketName();
				String keyName = object.getKey();

				GetObjectTaggingRequest request = new GetObjectTaggingRequest(bucketName, keyName);
				GetObjectTaggingResult result = s3.getObjectTagging(request);
				List<Tag> tags = result.getTagSet();
				String type = null;
				String name = null;

				if (tags.isEmpty()) {
					object.close();
					logger.warn("Error, S3 object: {} has no tags", keyName);
				} else {
					for (Tag tag : tags) {
						String key = tag.getKey();
						String value = tag.getValue();

						if (key.equals("type")) {
							type = value;
						}

						if (key.equals("name")) {
							name = value;
						}
					}

					if (name == null) {
						object.close();
						throw new Exception("Error: " + keyName + " is missing a name tag");
					}
					if (name.contains(" ")) {
						object.close();
						throw new Exception("Error: " + keyName + " has a name attribute with a space in it");
					}
					if (type == null) {
						object.close();
						throw new Exception("Error: " + keyName + " has no type tag");
					}

					if (!name.startsWith("$"))
						name = "$" + name;

					// The runnable will call object.close();
					Runnable w = new AwsWorker(type, name, object, size);
					executor.execute(w);

					count++;
				}
			}
		}
		executor.shutdown();
		executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

		time = System.currentTimeMillis() - time;
		time = time / 60000;
		logger.info("Initialized all {} S3 objects in {} minutes", count, time);
	}

	/**
	 * Initialized a template bid request. This is added to the seatlist.
	 *
	 * @param x Map. Definition of the seat.
	 * @throws Exception on parsing errors.
	 */
	public static void instanceBidRequest(Map x) throws Exception {
		String seatId = (String) x.get("id");
		String className = (String) x.get("bid");
		int k = className.indexOf("=");
		String parts[] = new String[2];
		String uri = className.substring(0, k);
		className = className.substring(k + 1);
		String[] options = null;

		/**
		 * set up any options on the class string
		 */
		if (className.contains("&")) {
			parts = className.split("&");
			className = parts[0].trim();
			options = parts[1].split(",");
			for (int ind = 0; ind < options.length; ind++) {
				options[ind] = options[ind].trim();
			}
		}

		String[] tags = uri.split("/");
		String exchange = tags[tags.length - 1];

		String name = (String) x.get("name");
		if (name == null)
			name = exchange;

		String id = (String) x.get("id");
		seats.put(name, id);

		try {
			Class<?> c = Class.forName(className);
			BidRequest br = (BidRequest) c.newInstance();
			if (br == null) {
				throw new Exception("Could not make new instance of: " + className);
			}

			/**
			 * Handle generic-ized and virtual exchanges
			 */
			if (className.contains("Generic") || className.contains("Virtual")) {
				br.setExchange(exchange);
				br.usesEncodedAdm = true;
			}

			Map extension = (Map) x.get("extension");
			if (extension != null) {
				br.handleConfigExtensions(extension);
			}

			RTBServer.exchanges.put(uri, br);

			if (parts[0] != null) {
				for (int ind = 1; ind < parts.length; ind++) {
					String option = parts[ind];
					String[] tuples = option.split("=");
					switch (tuples[0]) {
					case "usesEncodedAdm":
						br.usesEncodedAdm = true;
						break;
					case "!usesEncodedAdm":
						br.usesEncodedAdm = false;
						break;
					case "rlog":
						Double rlog = Double.parseDouble(tuples[1]);
						ExchangeLogLevel.getInstance().setExchangeLogLevel(name, rlog.intValue());
						break;
					case "useStrings":
						break;
					case "!useStrings":
						break;
					case "!usesPiggyBackWins":
						break;
					case "usesPiggyBackWins":
						BidRequest.setUsesPiggyBackWins(name);
						break;
					default:
						System.err.println("Unknown request: " + tuples[0] + " in definition of " + className);
					}
				}
			}

			/**
			 * Appnexus requires additional support for ready, pixel and click
			 */
			if (className.contains("Appnexus")) {
				RTBServer.exchanges.put(uri + "/ready", new Appnexus(Appnexus.READY));
				RTBServer.exchanges.put(uri + "/pixel", new Appnexus(Appnexus.PIXEL));
				RTBServer.exchanges.put(uri + "/click", new Appnexus(Appnexus.CLICK));
				RTBServer.exchanges.put(uri + "/delivered", new Appnexus(Appnexus.DELIVERED));
				Appnexus.seatId = seatId;
			}

		} catch (Exception error) {
			System.err.println("Error configuring exchange: " + name + ", error = ");
			throw error;
		}
	}

	public static String readData(String fileName) throws Exception {
		String message = "";
		int i = fileName.indexOf(".");
		if (i == -1)
			throw new Exception("Filename is missing type field");
		String type = fileName.substring(i);
		NavMap map;
		SimpleMultiset set;
		SimpleSet sset;
		Bloom b;
		Cuckoo c;
		switch (type) {
		case "range":
			map = new NavMap(fileName, fileName, false);
			message = "Added NavMap " + fileName + ": from file, has " + map.size() + " members";
			break;
		case "cidr":
			map = new NavMap(fileName, fileName, true);
			message = "Added NavMap " + fileName + ": from file, has " + map.size() + " members";
			break;
		case "bloom":
			b = new Bloom(fileName, fileName);
			message = "Initialize Bloom Filter: " + fileName + " from file, members = " + b.getMembers();
			break;
		case "cuckoo":
			c = new Cuckoo(fileName, fileName);
			break;
		case "multiset":
			set = new SimpleMultiset(fileName, fileName);
			message = "Initialize Multiset " + fileName + " from file, entries = " + set.getMembers();
			break;
		case "set":
			sset = new SimpleSet(fileName, fileName);
			message = "Initialize Multiset " + fileName + " from file, entries = " + sset.size();
			break;

		default:
			message = "Unknown type: " + type;
		}
		logger.info("*** {}", message);
		return message;
	}

	public static String readData(String type, String name, S3Object object, long size) throws Exception {
		String message = "";
		switch (type) {
		case "range":
		case "cidr":
			NavMap map = new NavMap(name, object, type);
			message = "Added NavMap " + name + ": has " + map.size() + " members";
			break;
		case "set":
			SimpleSet set = new SimpleSet(name, object);
			message = "Initialize Set: " + name + " from S3, entries = " + set.size();
			break;
		case "bloom":
			Bloom b = new Bloom(name, object, size);
			message = "Initialize Bloom Filter: " + name + " from S3, members = " + b.getMembers();
			break;

		case "cuckoo":
			Cuckoo c = new Cuckoo(name, object, size);
			message = "Initialize Cuckoo Filter: " + name + " from S3, entries = " + c.getMembers();
			break;
		case "multiset":
			SimpleMultiset ms = new SimpleMultiset(name, object);
			message = "Initialize Multiset " + name + " from S3, entries = " + ms.getMembers();
			break;
		default:
			message = "Unknown type: " + type;
		}
		logger.info("*** {}", message);
		return message;
	}

	public int requstLogStrategyAsInt(String x) {
		switch (x) {
		case "all":
			return REQUEST_STRATEGY_ALL;
		case "bids":
			return REQUEST_STRATEGY_BIDS;
		case "wins":
			return REQUEST_STRATEGY_WINS;
		}
		return REQUEST_STRATEGY_ALL;
	}

	public void initializeLookingGlass(List<Map> list) throws Exception {
		for (Map m : list) {
			String fileName = (String) m.get("filename");
			if (!fileName.equals("")) {
				String name = (String) m.get("name");
				String type = (String) m.get("type");
				if (name.startsWith("@") == false)
					name = "@" + name;
				if (type.contains("NavMap") || type.contains("RangeMap")) {
					new NavMap(name, fileName, false); // file uses ranges
				} else if (type.contains("CidrMap")) { // file uses CIDR blocks
					new NavMap(name, fileName, true);
				} else if (type.contains("AdxGeoCodes")) {
					new AdxGeoCodes(name, fileName);
				} else if (type.contains("LookingGlass")) {
					new LookingGlass(name, fileName);
				} else {
					// Ok, load it by class name
					Class cl = Class.forName(type);
					Constructor<?> cons = cl.getConstructor(String.class, String.class);
					cons.newInstance(name, fileName);
				}
				logger.info("*** Configuration Initialized {} with {}", name, fileName);
			}
		}
	}

	/**
	 * Purpose is to test if the Cache2k system is usable with the win URL specified
	 * in the configuration file.
	 *
	 * @throws Exception if the Win URL is not set to this instance.
	 */
	public void testWinUrlWithCache2k() throws Exception {
		String test = null;
		HttpPostGet hp = new HttpPostGet();
		String[] parts = winUrl.split("/");
		test = "http://" + parts[2] + "/info";
		test = hp.sendGet(test, 5000, 5000);
		if (test == null) {
			throw new Exception("Info on " + test + " failed!");
		}
		Map m = DbTools.mapper.readValue(test, Map.class);
		test = (String) m.get("from");
		if (test.equals(instanceName) == false) {
			throw new Exception("Win URL must resolve this instance if using Cache2K!, instead it is: " + test
					+ ", expecting " + instanceName);
		}

	}

	/**
	 * Used to load ./database.json into Cache2k. This is used when aerospike is not
	 * present. This instance will handle its own cache, and do its own win
	 * processing.
	 *
	 * @param fname String. The file name of the database.
	 * @throws Exception on file or cache2k errors.
	 */
	private List<String> readDatabaseIntoCache(String fname) {
		List<String> camps = new ArrayList();
		try {
			String content = new String(Files.readAllBytes(Paths.get(fname)), StandardCharsets.UTF_8);
			content = substitute(content);

			logger.info("Sample DB: {}", content);
			Database db = Database.getInstance();

			List<Campaign> list = DbTools.mapper.readValue(content,
					DbTools.mapper.getTypeFactory().constructCollectionType(List.class, Campaign.class));
			db.update(list);
			for (Campaign camp : list) {
				camps.add(camp.adId);
			}
		} catch (Exception error) {
			error.printStackTrace();
			logger.warn("Initial database {} not read, error: {}", fname, error.getMessage());
		}
		return camps;
	}

	/**
	 * Return the instance of Configuration, and if necessary, instantiates it
	 * first.
	 *
	 * @param fileName String. The name of the initialization file.
	 * @return Configuration. The instance of this singleton.
	 * @throws Exception on JSON errors.
	 */
	public static Configuration getInstance(String fileName) throws Exception {
		if (theInstance == null) {
			synchronized (Configuration.class) {
				if (theInstance == null) {
					theInstance = new Configuration();
					theInstance.initialize(fileName);
					try {
						theInstance.shell = new JJS();
					} catch (Exception error) {

					}
				} else
					theInstance.initialize(fileName);
			}
		}
		return theInstance;
	}

	/**
	 * Get an instance of the configuration object, using the specified config file,
	 * shard name and http poty
	 *
	 * @param fileName String. The filename of the configuration file.
	 * @param shard    String. The shard name for this instance.
	 * @param port     int. The HTTP port byumber
	 * @return Configuration singleton.
	 * @throws Exception on file errors and JSON errors.
	 */
	public static Configuration getInstance(String fileName, String shard, int port, int sslPort, String exchanges)
			throws Exception {
		if (theInstance == null) {
			synchronized (Configuration.class) {
				if (theInstance == null) {
					theInstance = new Configuration();
					try {
						theInstance.initialize(fileName, shard, port, sslPort, exchanges);
						theInstance.shell = new JJS();
					} catch (Exception error) {
						error.printStackTrace();
					}
				} else
					theInstance.initialize(fileName);
			}
		}
		return theInstance;
	}

	/**
	 * Handle specialized encodings, like those needed for Smaato
	 */
	public void encodeTemplates() throws Exception {
		Map m = (Map) template.get("exchange");
		if (m == null)
			return;
		Set set = m.keySet();
		Iterator<String> it = set.iterator();
		while (it.hasNext()) {
			String key = it.next();
			String value = (String) m.get(key);

			MacroProcessing.findMacros(macros, value);

			if (key.equalsIgnoreCase("smaato") || key.equalsIgnoreCase("smaaato")) {
				encodeSmaato(value);
			}
		}

		MacroProcessing.findMacros(macros, "{creative_ad_width} {creative_ad_height}");
	}

	/**
	 * For each of the seats, find out which template to use
	 */
	void encodeTemplateStubs() {
		Map m = (Map) template.get("exchange");
		String defaultStr = (String) template.get("default");

		Iterator<String> sr = seats.keySet().iterator();
		while (sr.hasNext()) {
			String key = sr.next();
			String value = (String) m.get(key);
			if (value == null)
				masterTemplate.put(key, defaultStr);
			else
				masterTemplate.put(key, value);

		}

	}

	/**
	 * Encode the smaato campaign variables.
	 *
	 * @param value String. The string of javascript to execute.
	 * @throws Exception on JavaScript errors.
	 */
	private void encodeSmaato(String value) throws Exception {
		NashHorn scripter = new NashHorn();
		scripter.setObject("c", this);
		String[] parts = value.split(";");

		// If it starts with { then it's not the campaign will encode smaato
		// itself
		if (value.startsWith("{"))
			return;

		for (String part : parts) {
			part = "c.SMAATO" + part.trim();
			part = part.replaceAll("''", "\"");
			scripter.execute(part);
		}
	}

	/**
	 * Return the configuration instance.
	 *
	 * @return The instance.
	 */
	public static Configuration getInstance() {
		if (theInstance == null)
			return null; // throw new RuntimeException("Please initialize the Configuration instance
							// first.");
		return theInstance;
	}

	/**
	 * Is the configuration object initialized.
	 *
	 * @return boolean. Returns true of initialized, else returns false.
	 */
	public static boolean isInitialized() {
		if (theInstance == null)
			return false;
		return true;

	}

	/**
	 * Returns an input stream from the file of the given name.
	 *
	 * @param fname String. The fully qualified file name.
	 * @return InputStream. The stream to read from.
	 * @throws Exception on file errors.
	 */
	public static InputStream getInputStream(String fname) throws Exception {
		File f = new File(fname);
		return new FileInputStream(f);
	}

	/**
	 * Can this campaign id bid? If it's instantaneous spend rate exceeds the
	 * campaign setting, then no, it can't/
	 *
	 * @param adid String. The adid of the campaign.
	 * @return boolean. Returns true if the campaign can bid.
	 */
	public boolean canBid(String adid) {
		return handyMap.canBid(adid, 0);
	}

	/**
	 * This deletes a campaign from the campaignsList (the running commands) this
	 * does not delete from the database.
	 *
	 * @param name String. The id of the campaign to delete
	 * @return boolean. Returns true if the campaign was found, else returns false.
	 */
	public boolean deleteCampaign(String name) throws Exception {
		List<Campaign> deletions = new ArrayList<Campaign>();
		Iterator<Campaign> it = campaignsList.iterator();

		for (Campaign c : campaignsList) {
			if (c != null && c.adId.equals(name)) {
				campaignsList.remove(c);
				overrideList.remove(c);
				recompile();
				return true;
			}
		}

		return false;
	}

	/**
	 * Set the weights of a campaign.
	 *
	 * @param name    String. The name of the campaign.
	 * @param weights String weights. In the form crid=x,crid=y,crid=z...
	 * @return boolean. Returns true if the assignment worked, else it returns
	 *         false.
	 * @throws Exception on parsing errors.
	 */
	public boolean setWeights(String name, String weights) throws Exception {
		for (Campaign c : campaignsList) {
			if (c != null && c.adId.equals(name)) {
				c.setWeights(weights);
				return true;
			}
		}

		return false;
	}

	/**
	 * Get the weights set on a campaign.
	 *
	 * @param name String. The name of the campaign,
	 * @return ProportionalEntry. The PE weights.
	 * @throws Exception if campaign is not found.
	 */
	public ProportionalEntry getWeights(String name) throws Exception {
		for (Campaign c : campaignsList) {
			if (c != null && c.adId.equals(name)) {
				if (c.weights == null) {
					return null;
				}
				return c.weights;
			}
		}
		throw new Exception("No such campaign: " + name);
	}

	/**
	 * Recompile the bid attributes we will parse from bid requests, based on the
	 * aggregate of all campaign bid constraints.
	 */
	public void recompile() throws Exception {
		int percentage = RTBServer.percentage.intValue(); // save the current
															// throttle
		// RTBServer.percentage = new AtomicLong(0); // throttle the bidder to 0
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
		} // Wait for the working campaigns to drain
		BidRequest.compile(); // modify the Map of bid request components.
		// RTBServer.percentage = new AtomicLong(percentage); // restore the old
		// percentage
	}

	/**
	 * Return the EFFECTIVE campaigns list. If this is not an exchange specific
	 * list, then returns the campaignsList, otherwise it returns the overrideList.
	 *
	 * @return List. The list of campaigns.
	 */
	public List<Campaign> getCampaignsList() {
		if (overrideExchanges == null)
			return campaignsList;
		else
			return overrideList;
	}

	/**
	 * Sort the rules for selecting campaigns and creatives in descending order, so
	 * we can shorten the time to no-bid
	 */
	public void sortCampaignsAndCreatives() {
		boolean state = RTBServer.stopped;
		RTBServer.stopped = true;

		// Don't wait if the server is already stopped for some reason
		try {
			if (state == false)
				TimeUnit.SECONDS.sleep(2);
		} catch (Exception error) {
			error.printStackTrace();
			return;
		}

		for (int i = 0; i < campaignsList.size(); i++) {
			campaignsList.get(i).sortNodes();
		}

		RTBServer.stopped = state;
	}

	/**
	 * Return the actual backing campaigmsList
	 *
	 * @return List. The Campaigns list.
	 */
	public List<Campaign> getCampaignsListReal() {
		return campaignsList;
	}

	public void clearCampaigns() {
		campaignsList.clear();
		overrideList.clear();
	}

	/**
	 * Add a campaign to the list of campaigns we are running. Does not add to
	 * Aerospike.
	 *
	 * @param c Campaign. The campaign to add into the accounting.
	 * @throws Exception if the encoding of the attributes fails.
	 */
	public void addCampaign(Campaign c) throws Exception {
		if (c == null)
			return;

		handyMap.addCampaign(c);

		for (int i = 0; i < campaignsList.size(); i++) {
			Campaign test = campaignsList.get(i);
			if (test.adId.equals(c.adId)) {
				campaignsList.remove(i);
				overrideList.remove(test);
				break;
			}
		}

		c.encodeCreatives();
		c.encodeAttributes();
		campaignsList.add(c);

		if (overrideExchanges != null) {
			for (String e : overrideExchanges) {
				if (c.canUseExchange(e)) {
					overrideList.add(c);
					break;
				}
			}
		}

		recompile();
	}

	/**
	 * Quickly add a campaign to the list of campaigns we are running, does not do
	 * checks or recompile. Use this when initially loading the campaign.
	 *
	 * @param c Campaign. The campaign to add into the accounting.
	 * @throws Exception if the encoding of the attributes fails.
	 */
	public void fastAddCampaign(Campaign c) throws Exception {
		if (c == null)
			return;

		handyMap.addCampaign(c);

		c.encodeCreatives();
		c.encodeAttributes();
		campaignsList.add(c);

		if (overrideExchanges != null) {
			for (String e : overrideExchanges) {
				if (c.canUseExchange(e)) {
					overrideList.add(c);
					break;
				}
			}
		}

		if (RTBServer.trace) {
			logger.info("***************** CAMPAIGN ********************");
			logger.info(c.toJson());
		}

	}

	/**
	 * A horrible hack to find out the ad type.
	 *
	 * @param adid String. The ad id.
	 * @param crid String. The creative id.
	 * @return String. Returns the type, or, null if anything goes wrong.
	 */
	public String getAdType(String adid, String crid) {
		return handyMap.getAdType(adid, crid);
	}

	/**
	 * Efficiently add a list of campaigns to the system
	 *
	 * @param campaigns String[]. The array of campaign adids to load.
	 * @throws Exception on Database errors.
	 */
	public synchronized String addCampaignsList(String[] campaigns) throws Exception {
		String rets = "";
		ExecutorService executor = Executors.newFixedThreadPool(2);

		CampaignBuilderWorker.total = campaigns.length;
		CampaignBuilderWorker.counter = 0;
		RTBServer.stopped = true;
		for (String adid : campaigns) {
			Runnable w = new CampaignBuilderWorker(adid);
			rets += adid + " ";
			executor.execute(w);
		}
		executor.shutdown();
		while (!executor.isTerminated()) {
		}
		recompile();
		RTBServer.stopped = false;

		logger.info("Mass load of campaigns complete {}", campaigns);
		return rets;
	}

	/**
	 * Is the identified campaign running?
	 *
	 * @param owner String. The campaign owner
	 * @param name  String. The campaign adid.
	 * @return boolean. Rewturns true if it is loaded, else false.
	 */
	public boolean isRunning(String owner, String name) {
		for (Campaign c : campaignsList) {
			if (c.owner.equals(owner) && c.adId.equals(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns a list of all the campaigns that are running
	 *
	 * @return List. The list of campaigns, byadIds, that are running.
	 */
	public List<String> getLoadedCampaignNames() {
		List<String> list = new ArrayList<String>();
		for (Campaign c : campaignsList) {
			list.add(c.adId);
		}
		return list;
	}

	/**
	 * Add a campaign to the campaigns list using the shared map database of
	 * campaigns
	 *
	 * @param name String. The name of the campaign.
	 * @throws Exception if the addition of this campaign fails.
	 */
	public void addCampaign(String name) throws Exception {
		List<Campaign> list = Database.getInstance().getCampaigns();
		for (Campaign c : list) {
			if (name.length() == 0 || c.adId.matches(name)) {
				// deleteCampaign(name);
				addCampaign(c);
				logger.info("Loaded  {}", c.adId);
			}
		}
	}

	/**
	 * Quickly load a campaign to the campaigns list using the shared map database
	 * of campaigns. Use this on initial loads, it avoids checks and recompiles.
	 *
	 * @param name String. The name of the campaign.
	 * @throws Exception if the addition of this campaign fails.
	 */
	public void fastAddCampaign(String name) throws Exception {
		List<Campaign> list = Database.getInstance().getCampaigns();
		for (Campaign c : list) {
			if (name.length() == 0 || c.adId.matches(name)) {
				fastAddCampaign(c);
				logger.info("Loaded  {}", c.adId);
			}
		}
	}

	/**
	 * Return your IP address by posting to api.externalip.net
	 *
	 * @return String. The IP address of this instance.
	 */
	public static String getIpAddress() {
		URL myIP;

		if (myIpAddress != null)
			return myIpAddress;

		try {
			myIP = new URL("http://api.externalip.net/ip/");

			BufferedReader in = new BufferedReader(new InputStreamReader(myIP.openStream()));
			myIpAddress = in.readLine();
		} catch (Exception e) {
			try {
				myIP = new URL("http://myip.dnsomatic.com/");

				BufferedReader in = new BufferedReader(new InputStreamReader(myIP.openStream()));
				myIpAddress = in.readLine();
			} catch (Exception e1) {
				try {
					myIP = new URL("http://icanhazip.com/");

					BufferedReader in = new BufferedReader(new InputStreamReader(myIP.openStream()));
					myIpAddress = in.readLine();
				} catch (Exception e2) {
					e2.printStackTrace();
				}
			}
		}

		return myIpAddress;
	}
}

/**
 * Created by ben on 7/17/17.
 */

class CampaignBuilderWorker implements Runnable {

	static volatile int counter;
	static int total;
	/** Logging object */
	static final Logger logger = LoggerFactory.getLogger(CampaignBuilderWorker.class);

	private String adid;
	private String msg;

	public CampaignBuilderWorker(String adid) {
		this.adid = adid;
	}

	@Override
	public void run() {
		msg = "";
		try {
			Campaign camp = WebCampaign.getInstance().db.getCampaign(adid);
			Configuration.getInstance().addCampaign(camp);
		} catch (Exception error) {
			logger.error("Error creating campaign: {}", error.toString());
		}

	}

	@Override
	public String toString() {
		return msg;
	}
}
