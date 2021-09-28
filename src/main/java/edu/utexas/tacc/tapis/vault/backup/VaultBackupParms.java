package edu.utexas.tacc.tapis.vault.backup;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.net.URL;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class VaultBackupParms 
{
    /* ********************************************************************** */
    /*                               Constants                                */
    /* ********************************************************************** */
    // Number of hours between backups.
    private static final int DEFAULT_PERIOD_IN_HOURS = 24;
    private static final int DEFAULT_MAX_COPIES = 10;
    
    // Output directory for backup snapshots.
    private static final String DEFAULT_OUTPUT_DIR = "/vaultBackups";
    
    // Email notification info.
    private static final int DEFAULT_SMTP_PORT = 25;
    
    /* ********************************************************************** */
    /*                                 Fields                                 */
    /* ********************************************************************** */
    @Option(name = "-url", required = true, aliases = {"-urlvault"}, 
            usage = "URL to Vault instance including port number")
    public String url;

    @Option(name = "-start", required = true, aliases = {"-starttime"}, 
            usage = "Start time expressed as HH:MM")
    public String startTime;
    
    @Option(name = "-prefix", required = true, aliases = {"-fileprefix"}, 
            usage = "Prefix for backup file name (usually stage or prod)")
    public String filePrefix;
    
    @Option(name = "-token", required = false, aliases = {"-tokenfile"}, 
            usage = "File containing token (deleted after reading)")
    public String tokenFile;    
    
    @Option(name = "-out", required = false, aliases = {"-outdir"}, 
            usage = "Output directory for backed up data (default $HOME/vaultBackups)")
    public String outDir;    
    
    @Option(name = "-log", required = false, aliases = {"-logdir"}, 
            usage = "Directory for VaultBackup.out & VaultBackupProcess.out (default $HOME)")
    public String logDir;    
    
    @Option(name = "-noconsole", required = false,  
            usage = "If true, won't log to stdout")
    public boolean noConsole = false;
    
    @Option(name = "-period", required = false, aliases = {"-hrs, -hours"}, 
            usage = "Hours between backups (>= 1)")
    public int period = DEFAULT_PERIOD_IN_HOURS;
    
    @Option(name = "-now", required = false, aliases = {"-once"}, 
            usage = "Run backup immediately and then exit")
    public boolean now = false;
    
    @Option(name = "-copies", required = false, aliases = {"-maxcopies"}, 
            usage = "Maximum backup copies to keep locally")
    public int maxCopies = DEFAULT_MAX_COPIES;    
    
    @Option(name = "-smtp-to", required = false, 
            depends = {"-smtp-host"},
            usage = "Email address to recieve notification")
    public String smtpTo;
    
    @Option(name = "-smtp-host", required = false,
            depends = {"-smtp-to"},
            usage = "SMTP outbound mail server (no authentication)")
    public String smtpHost;
    
    @Option(name = "-smtp-port", required = false,
            usage = "Port on outbound mail server")
    public int smtpPort = DEFAULT_SMTP_PORT;
    
    @Option(name = "-dry", required = false, aliases = {"-dryrun"}, 
            usage = "Run but don't backup or send email.")
    public boolean dryrun = false;
    
    @Option(name = "-help", aliases = {"--help"}, 
            usage = "Display help information")
    public boolean help;
    
    // Calculated fields.
    private int startTimeHour;
    private int startTimeMinute;
    
    /* ********************************************************************** */
    /*                              Constructors                              */
    /* ********************************************************************** */
    /* ---------------------------------------------------------------------- */
    /* constructor:                                                           */
    /* ---------------------------------------------------------------------- */
    VaultBackupParms(String[] args) 
     throws Exception
    {
      initializeParms(args);
      validateParms();
    }
    
    /* ********************************************************************** */
    /*                             Package Methods                            */
    /* ********************************************************************** */
    /* ---------------------------------------------------------------------- */
    /* printArguments:                                                        */
    /* ---------------------------------------------------------------------- */
    String printArguments()
    {
        String s = "\nVaultBackup arguments:\n" +
                   "\nVault URL: " + url + 
                   "\nStart time: " + startTime +
                   "\nHours between backups: " + period +
                   "\nMaximum local copies: " + maxCopies +
                   "\nFile prefix: " + filePrefix +
                   "\nOutput directory: " + outDir +
                   "\nLog directory: " + logDir +
                   "\nBackup now then exit: " + now +
                   "\nDry run: " + dryrun +
                   "\nSMTP recipient email: " + smtpTo +
                   "\nSMTP mail server host: " + smtpHost +
                   "\nSMTP mail server port: " + smtpPort +
                   "\n\n";
        return s;
    }
    
    // Accessors.
    int getStartHour()   {return startTimeHour;}
    int getStartMinute() {return startTimeMinute;}
    
    /* ********************************************************************** */
    /*                               Private Methods                          */
    /* ********************************************************************** */
    /* ---------------------------------------------------------------------- */
    /* initializeParms:                                                       */
    /* ---------------------------------------------------------------------- */
    /** Parse the input arguments. */
    private void initializeParms(String[] args)
        throws Exception
    {
      // Get a command line parser to verify input.
      CmdLineParser parser = new CmdLineParser(this);
      parser.getProperties().withUsageWidth(120);
      
      try {
         // Parse the arguments.
         parser.parseArgument(args);
        }
       catch (CmdLineException e)
        {
         if (!help)
           {
            // Create message buffer of sufficient size.
            final int initialCapacity = 1024;
            StringWriter writer = new StringWriter(initialCapacity);
            
            // Write parser error message.
            writer.write("\n******* Input Parameter Error *******\n");
            writer.write(e.getMessage());
            writer.write("\n\n");
            
            // Write usage information--unfortunately we need an output stream.
            writer.write("VaultBackup [options...]\n");
            ByteArrayOutputStream ostream = new ByteArrayOutputStream(initialCapacity);
            parser.printUsage(ostream);
            try {writer.write(ostream.toString("UTF-8"));}
              catch (Exception e1) {}
            writer.write("\n");
            
            // Throw exception.
            throw new Exception(writer.toString());
           }
        }
      
      // Display help and exit program.
      if (help)
        {
         String s = "\nVaultBackup for backup Hashicorp Vault raft database.";
         System.out.println(s);
         System.out.println("\nVaultBackup [options...]\n");
         parser.printUsage(System.out);
         
         // Add a usage blurb.
         s = "\n\nProvide a start time and the Vault URL to begin the backup cycle." +
             "\nYou will be prompted for a root Vault token\n.";
         System.out.println(s);
         System.exit(0);
        }
    }
    
    /* ---------------------------------------------------------------------- */
    /* validateParms:                                                         */
    /* ---------------------------------------------------------------------- */
    /** Check the semantic integrity of the input parameters. 
     * 
     * @throws JobException
     */
    private void validateParms()
     throws Exception
    {
        // Parse start time.
        var components = startTime.split(":");
        if (components == null || components.length < 1) 
            throw new IllegalArgumentException("Invalid startTime argument: " + startTime + ".");
        
        // Assign the start hours and minutes.
        startTimeHour = Integer.valueOf(components[0]);
        if (startTimeHour < 0 || startTimeHour > 23)
            throw new IllegalArgumentException("Invalid start hour: " + startTimeHour + ".");
        if (components.length > 1) {
            startTimeMinute = Integer.valueOf(components[1]);
            if (startTimeMinute < 0 || startTimeMinute > 59)
                throw new IllegalArgumentException("Invalid start minute: " + startTimeMinute + ".");
        }
        
        // Set the log and backup directories if they are set.
        if (StringUtils.isBlank(logDir)) logDir = System.getProperty("user.home");
        if (StringUtils.isBlank(outDir)) outDir = System.getProperty("user.home") + DEFAULT_OUTPUT_DIR;
        
        // Make sure the period is between 1 and 24.
        if (period < 1) 
            throw new IllegalArgumentException("Period must be at least 1 hour: " + period + ".");
        
        // The output directory must be an absolute path.
        if (!outDir.startsWith("/"))
            throw new IllegalArgumentException("The output directory must be an absolute path: " + outDir + ".");
        
        // Check the url for being well-formed.
        URL u = new URL(url);
    }
}
