package edu.utexas.tacc.tapis.vault.backup;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.net.URL;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class VaultBackupParms 
{
    /* ********************************************************************** */
    /*                               Constants                                */
    /* ********************************************************************** */
    // Number of hours between backups.
    private static final int DEFAULT_HOURS_BETWEEN_BACKUPS = 24;
    private static final int DEFAULT_MAX_COPIES = 10;
    
    // Output directory for backup snapshots.
    private static final String DEFAULT_OUTPUT_DIR = "/root/vaultBackups";
    
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
    
    @Option(name = "-dir", required = false, aliases = {"-outdir"}, 
            usage = "Output directory")
    public String outputDir = DEFAULT_OUTPUT_DIR;    
    
    // Negative hours are interpreted as minutes (good for testing).
    @Option(name = "-hrs", required = false, aliases = {"-hours"}, 
            usage = "Number of hours between backups")
    public int hours = DEFAULT_HOURS_BETWEEN_BACKUPS;
    
    @Option(name = "-copies", required = false, aliases = {"-maxcopies"}, 
            usage = "Maximum backup copies to keep locally")
    public int maxCopies = DEFAULT_MAX_COPIES;
    
    @Option(name = "-email", required = false, aliases = {"-support"}, 
            usage = "Support email to recieve notification")
    public String email;
    
    @Option(name = "-help", aliases = {"--help"}, 
            usage = "Display help information")
    public boolean help;
    
    // Calculated fields.
    private int startTimeHours;
    private int startTimeMinutes;
    
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
      printArguments();
    }
    
    /* ---------------------------------------------------------------------- */
    /* printArguments:                                                        */
    /* ---------------------------------------------------------------------- */
    void printArguments()
    {
        String s = "\nVaultBackup arguments:\n" +
                   "\nVault URL: " + url + 
                   "\nStart time: " + startTime +
                   "\nHours between backups: " + hours +
                   "\nMaximum local copies: " + maxCopies +
                   "\nSupport email: " + email +
                   "\n\n";
        System.out.println(s);
    }
    
    /* **************************************************************************** */
    /*                               Private Methods                                */
    /* **************************************************************************** */
    /* ---------------------------------------------------------------------------- */
    /* initializeParms:                                                             */
    /* ---------------------------------------------------------------------------- */
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
         String s = "\nVaultBackup for backup up Hashicorp Vault raft database.";
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
        startTimeHours = Integer.valueOf(components[0]);
        if (startTimeHours < 0 || startTimeHours > 23)
            throw new IllegalArgumentException("Invalid start hour: " + startTimeHours + ".");
        if (components.length > 1) {
            startTimeMinutes = Integer.valueOf(components[1]);
            if (startTimeMinutes < 0 || startTimeMinutes > 59)
                throw new IllegalArgumentException("Invalid start minute: " + startTimeMinutes + ".");
        }
        
        // Check the url for being well-formed.
        URL u = new URL(url);
    }
}
