package edu.utexas.tacc.tapis.vault.backup;

import java.io.BufferedWriter;
import java.io.Console;
import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetAddress;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;

import edu.utexas.tacc.tapis.shared.providers.email.EmailClientParameters;
import edu.utexas.tacc.tapis.shared.providers.email.clients.SMTPEmailClient;
import edu.utexas.tacc.tapis.shared.providers.email.enumeration.EmailProviderType;
import edu.utexas.tacc.tapis.shared.utils.HTMLizer;

public final class VaultBackup 
{
    /* ********************************************************************** */
    /*                               Constants                                */
    /* ********************************************************************** */
    // File name components.
    private static String BACKUP_FILENAME_STUB = "-vault-backup-";
    private static String BACKUP_FILENAME_EXT  = ".snap";
    
    // Output files.
    private static String LOG_FILE = "VaultBackup.out";
    private static String CMD_OUTPUT_FILE = "VaultBackupCommand.out";
    
    // A way to limit backup frequency in case things get weird.
    private static final int MIN_SLEEP_SECONDS = 120;
    
    // Process builder error code.
    private static final int PROCESS_BUILDER_ERROR = 666;
    
    // Email constants.
    private static final String EMAIL_FROM_ADDR = "vault-backup@tacc.cloud";
    private static final String EMAIL_TO_NAME   = "VaultBackup-Support";
    
    /* ********************************************************************** */
    /*                                Fields                                  */
    /* ********************************************************************** */
    // Command line arguments.
    private final VaultBackupParms _parms;
    
    // The user-specified start time.
    private final LocalTime        _startTime;
    
    // Set up the VaultBackup.out log file.
    private BufferedWriter         _logWriter;
    
    // For documentation purposes we record the deleted backups.
    private List<String>           _deletedBackupFiles = new ArrayList<String>();
    
    /* ********************************************************************** */
    /*                              Constructors                              */
    /* ********************************************************************** */
    /* ---------------------------------------------------------------------- */
    /* constructor:                                                           */
    /* ---------------------------------------------------------------------- */
    private VaultBackup(VaultBackupParms parms)
    {
        _parms = parms;
        _startTime = LocalTime.of(_parms.getStartHour(), _parms.getStartMinute());
    }
    
    /* ********************************************************************** */
    /*                             Public Methods                             */
    /* ********************************************************************** */
    /* ---------------------------------------------------------------------- */
    /* main:                                                                  */
    /* ---------------------------------------------------------------------- */
    public static void main(String[] args) throws Exception 
    {
        // Parse the command line parameters.
        VaultBackupParms parms = new VaultBackupParms(args);
        
        // Run the backups.
        var backup = new VaultBackup(parms);
        backup.execute();
    }

    /* ********************************************************************** */
    /*                             Private Methods                            */
    /* ********************************************************************** */
    /* ---------------------------------------------------------------------- */
    /* execute:                                                               */
    /* ---------------------------------------------------------------------- */
    private void execute()
    {
        // Open log file.
        openLogFile();
        log("Starting VaultBackup");
        
        // Log the command line arguments.
        log(_parms.printArguments());
        
        // Get Vault root token.
        String token = getToken();
        
        // Backup forever unless -now parameter is set.
        while (true) {
            // Get the current local date time.
            LocalDateTime localNow = LocalDateTime.now();
            
            // Get next backup time.
            LocalDateTime nextBackupTime = getNextBackupTime(localNow);
            
            // Determine how long to sleep.
            long sleepMillis = getSleepMillis(localNow, nextBackupTime);
            log("LocalTime=" + localNow + ", NextBackup=" + nextBackupTime +
                   ", SleepMilliseconds=" + sleepMillis);
            
            // Sleep until the next scheduled backup time.
            if (!_parms.dryrun && sleepMillis > 0) 
                try {Thread.sleep(sleepMillis);} 
                catch (InterruptedException e) {
                    // Ignore possibly spurious interrupts.
                    String msg = "Ignoring InterruptedException received while sleeping until next backup time: " +
                                 e.getMessage();
                    log(msg);
                    continue;
                }
            
            // Construct output file name.
            String backupFilename = getbackupFilename();
            
            // Issue the backup call.
            int rc = backupVault(backupFilename, token);
            
            // Remove old backups.
            if (rc == 0) removeBackups();
            
            // Send the backup email.
            sendEmail(backupFilename, rc);
            
            // Exit if this was a one time backup invocation,
            // otherwise wait for the next backup time.
            if (_parms.now || _parms.dryrun) break;
        }
        
        // Close log before exiting.
        closeLogFile();
    }

    /* ---------------------------------------------------------------------- */
    /* getToken:                                                              */
    /* ---------------------------------------------------------------------- */
    private String getToken()
    {
        String prompt = "Please enter a non-expiring Vault root token: ";
        String token = getInputFromConsole(prompt);
        if (StringUtils.isBlank(token)) throw new IllegalArgumentException("No token entered.");
        return token.strip();
    }
    
    /* ---------------------------------------------------------------------- */
    /* getNextBackupTime:                                                     */
    /* ---------------------------------------------------------------------- */
    private LocalDateTime getNextBackupTime(LocalDateTime localNow)
    {
        // Construct the starting point for the next backup.
        LocalDateTime nextDateTime = LocalDateTime.of(localNow.getYear(), 
                                                      localNow.getMonthValue(),
                                                      localNow.getDayOfMonth(),
                                                      _startTime.getHour(),
                                                      _startTime.getMinute());
        
        // Adjust the next date/time up or down depending on its relationship
        // to the current time and to the user-specified period.
        if (localNow.isBefore(nextDateTime))
            nextDateTime = adjustFutureNextDateTime(localNow, nextDateTime);
        else if (localNow.isAfter(nextDateTime))
            nextDateTime = adjustPastNextDateTime(localNow, nextDateTime);
        
        // In case the next backup time is exactly the current time,
        // we push the backup out 1 period.
        if (localNow.isEqual(nextDateTime))
            nextDateTime.plusHours(_parms.period);
        
        return nextDateTime;
    }
    
    /* ---------------------------------------------------------------------- */
    /* adjustFutureNextDateTime:                                              */
    /* ---------------------------------------------------------------------- */
    /** This method adjust DOWN the original nextDateTime value by period hours
     * zero or more times.  We incrementally adjust the backup time to the value
     * right before subtracting another period hours make the backup time earlier
     * than the current time.  
     * 
     * NOTE: This method can only be called when localNow is before the nextDateTime.
     *       For example, the nextDateTime is 10 and the localNow is 6.
     * 
     * @param localNow current time
     * @param nextDateTime user specified scheduled time (HH:MM)
     * @return the adjusted next backup time 
     */
    private LocalDateTime adjustFutureNextDateTime(LocalDateTime localNow, 
                                                   LocalDateTime nextDateTime)
    {
        // It's possible no adjustment will be made depending on the current
        // time is within a single period of the nextDateTime.
        while (true) {
            var candidate = nextDateTime.minusHours(_parms.period);
            if (localNow.isBefore(candidate)) {
                nextDateTime = candidate;
                continue;
            }
            
            // We've closed the gap between current time and the 
            // original next backup time as much as possible, so
            // we're done.
            break;
        }
        
        return nextDateTime;
    }
    
    /* ---------------------------------------------------------------------- */
    /* adjustPastNextDateTime:                                                */
    /* ---------------------------------------------------------------------- */
    /** This method adjusts UP the original nextDateTime value by period hours
     * one or more times.  We incrementally augment the backup time by period
     * hours until we encounter the first time that is after the current time.
     * 
     * NOTE: This method can only be called when localNow is after the nextDateTime.
     *       For example, the nextDateTime is 7 and the localNow is 11.
     * 
     * @param localNow current time
     * @param nextDateTime user specified scheduled time (HH:MM)
     * @return the adjusted next backup time 
     */
    private LocalDateTime adjustPastNextDateTime(LocalDateTime localNow, 
                                                 LocalDateTime nextDateTime)
    {
        // We know the localNow is before the original nextDateTime so
        // at least one candidate assignment will always be necessary.
        // Specifically, the first candidate that localNow is no longer
        // after is the one we want.
        while (true) {
            var candidate = nextDateTime.plusHours(_parms.period);
            if (localNow.isAfter(candidate)) {
                nextDateTime = candidate;
                continue;
            }
            
            // We've found the first time that is an integral number
            // of periods after the original nextDateTime and also after 
            // the current time.  We save the candidate and quit.
            nextDateTime = candidate;
            break;
        }
        
        return nextDateTime;
    }
    
    /* ---------------------------------------------------------------------- */
    /* getSleepMillis:                                                        */
    /* ---------------------------------------------------------------------- */
    /** Get the difference between the nextBackupTime and the current time,
     * the minimum allowed sleep time, or 0 if this is a one-off invocation.
     * 
     * @param localNow current time
     * @param nextBackupTime calculated next backup time
     * @return milliseconds to sleep before next backup time
     */
    private long getSleepMillis(LocalDateTime localNow, LocalDateTime nextBackupTime)
    {
        // Is this a one-off invocation?
        if (_parms.now) return 0;
        
        // Substract the two times using the same timezone setting.
        long diff = nextBackupTime.toEpochSecond(ZoneOffset.UTC) - 
                     localNow.toEpochSecond(ZoneOffset.UTC);
        // Let's limit the frequency that 
        if (diff < MIN_SLEEP_SECONDS) diff = MIN_SLEEP_SECONDS;
        return diff * 1000;
    }
    
    /* ---------------------------------------------------------------------- */
    /* getbackupFilename:                                                     */
    /* ---------------------------------------------------------------------- */
    private String getbackupFilename()
    {
        // Get the data in YYYYMMDD format.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String datePart = LocalDate.now().format(formatter);
        
        String fn = _parms.filePrefix + BACKUP_FILENAME_STUB + datePart;
        fn = getNextAvailableName(fn);
        return fn + BACKUP_FILENAME_EXT;
    }
    
    /* ---------------------------------------------------------------------- */
    /* getNextAvailableName:                                                  */
    /* ---------------------------------------------------------------------- */
    private String getNextAvailableName(String originalFilename)
    {
        // Initialize the candidate name.
        String candidate = originalFilename;
        
        // Add -N to the end of the candidate until we find a file that 
        // doesn't exist.
        int nextSeqNo = 0;
        while (true) {
            File f = new File(_parms.outDir, candidate + BACKUP_FILENAME_EXT);
            if (!f.exists()) break;
            candidate = originalFilename + "-" + ++nextSeqNo;
        }
        
        // Return the possibly altered original file name without extension.
        return candidate;
    }
    
    /* ---------------------------------------------------------------------------- */
    /* backupVault:                                                                 */
    /* ---------------------------------------------------------------------------- */
    private int backupVault(String backupFilename, String token)
    {
        // Assemble the command and its arguments.
        var cmdList = new ArrayList<String>(30);
//        cmdList.add("vault");
//        cmdList.add("operator");
//        cmdList.add("raft");
//        cmdList.add("snapshot");
//        cmdList.add("save");
//        cmdList.add(backupFilename);
        cmdList.add("env");
        
        // Create the process builder.
        var pb = new ProcessBuilder(cmdList);
        
        // Populate environment map.
        var env = pb.environment();
        env.put("VAULT_ADDR", _parms.url);
        env.put("VAULT_TOKEN", token);
        
        // Set the working directory to where the backups 
        // will be written.  This allows us to use a simple
        // file name in the above cmdList.
        pb.directory(new File(_parms.outDir));
        
        // Set i/o options for the spawned process.
        // The same file gets overwritten on each execution.
        pb.redirectErrorStream(true);
        var cmdOutFile = new File(_parms.logDir, CMD_OUTPUT_FILE);
        pb.redirectOutput(Redirect.to(cmdOutFile));
        
        // Log the command.
        log("Executing: " + String.join(" ", cmdList));

        // No actual backup when testing.
        if (_parms.dryrun) return 0;

        // Run the command.
        Process process = null;
        try {process = pb.start();}
            catch (Exception e) {
                String msg = "VaultBackup failed: " + e.getMessage();
                log(msg);
                return PROCESS_BUILDER_ERROR;
            }
        
        // Return the vault backup utility's exit code.
        return process.exitValue();
    }
    
    /* ---------------------------------------------------------------------------- */
    /* removeBackups:                                                               */
    /* ---------------------------------------------------------------------------- */
    private void removeBackups()
    {
        // Get a listing of files in the output directory.
        File outDir  = new File(_parms.outDir);
        File[] files = outDir.listFiles(new OutputFilenameFilter());
        
        // See if we are over the user's limit of backup files.
        if (files.length <= _parms.maxCopies) return;
        
        // Let's alphabetize the part of the file name starting with the date.
        // The earlier backups have alphabetically earlier names if we ignore
        // the user-chosen prefix.
        var map = new TreeMap<String,File>();
        for (var f : files) {
            String fn = f.getName();
            int index = fn.indexOf(BACKUP_FILENAME_STUB);
            if (index > 0) {
                String key = fn.substring(index);
                map.put(key, f);
            }
        }
        
        // Calculate the number of deletions required
        // and then delete that number of files from 
        // the beginning of the alphabetize map.
        int deleteCnt = map.size() - _parms.maxCopies;
        while (deleteCnt > 0) {
            var f = map.firstEntry().getValue();
            if (!_parms.dryrun) f.delete();  // No removal when testing.
            deleteCnt--;
            _deletedBackupFiles.add(f.getAbsolutePath());
        }
    }

    /* ---------------------------------------------------------------------------- */
    /* sendEmail:                                                                   */
    /* ---------------------------------------------------------------------------- */
    private void sendEmail(String backupFilename, int rc)
    {
        // No email when testing.
        if (_parms.dryrun) return;
        if (StringUtils.isBlank(_parms.smtpTo)) return;
        
        // Assign subject.
        var emailParms = new EmailParameters();
        String subject;
        if (rc == 0) subject = emailParms.getEmailFromName() + " BACKUP SUCCEEDED";
          else subject = emailParms.getEmailFromName() + " BACKUP FAILED";
        
        // Create body.
        String body = generateEmailBody(backupFilename, rc);
        
        // Best effort attempt.
        try {
            var client = new SMTPEmailClient(emailParms);
            client.send(emailParms.getEmailUser(),
                        _parms.smtpTo,
                        subject,
                        body, HTMLizer.htmlize(body));
        } catch (Exception e) {
            String msg = "\nFAILED to send email to " + _parms.smtpTo 
                         + " concerning backup to " + backupFilename 
                         + ": " + e.getMessage();
            log(msg);
        }
    }
    
    /* ---------------------------------------------------------------------------- */
    /* generateEmailBody:                                                           */
    /* ---------------------------------------------------------------------------- */
    private String generateEmailBody(String backupFilename, int rc)
    {
        // Best effort attempt to get host information.
        InetAddress inetAddress = null;
        String ipAddress = null;
        String hostName = null;
        try {
            inetAddress = InetAddress.getLocalHost();
            ipAddress = inetAddress.getHostAddress();
            hostName  = inetAddress.getHostName();
        } catch (Exception e) {}
        
        // Everything depends on whether the backup succeeded.
        String body;
        if (rc == 0) 
            body = "Successfully backed up Vault database to " + backupFilename +
                    " in directory " + _parms.outDir + ".\n\n";
        else 
            body = "FAILED to backed up Vault database to " + backupFilename +
                   " in directory " + _parms.outDir + ".\n\n";
            
        // Host info.
        if (hostName != null)  body += "Host: " + hostName + "\n";
        if (ipAddress != null) body += "IP: " + ipAddress + "\n";
        if (hostName != null || ipAddress != null) body += "\n";
            
        // This program's log file.
        if (_logWriter != null) {
            var logFile = new File(_parms.logDir, LOG_FILE);
            body += "The VaultBackup utility log is at: " +  logFile.getAbsolutePath() + "\n";
        }
            
        // Vault backup process log information.
        var cmdOutFile = new File(_parms.logDir, CMD_OUTPUT_FILE);
        body += "The Vault operator log is at: " + cmdOutFile.getAbsolutePath() + "\n"; 
            
        // Catalog deleted files.
        if (!_deletedBackupFiles.isEmpty()) {
             body += "\nThe following files were deleted to maintain a maximum of " 
                     + _parms.maxCopies + " backups locally:\n\n";
             for (String s : _deletedBackupFiles) body += "    - " + s + "\n";
        }
            
        return body;
    }
    
    /* ---------------------------------------------------------------------------- */
    /* log:                                                                         */
    /* ---------------------------------------------------------------------------- */
    private void log(String msg)
    {
        // Write to console and log file (if possible).
        System.out.println(msg);
        if (_logWriter != null) 
            try {
                _logWriter.write(msg);
                _logWriter.write("\n");
                _logWriter.flush();
            } catch (Exception e) {}
    }
    
    /* ---------------------------------------------------------------------------- */
    /* openLogFile:                                                                 */
    /* ---------------------------------------------------------------------------- */
    private void openLogFile()
    {
        // Open the log file for writing.
        try {_logWriter = new BufferedWriter(new FileWriter(new File(_parms.logDir, LOG_FILE)));}
            catch (Exception e) {
                String msg = "VaultBackup failed: " + e.getMessage();
                log(msg);
            }
    }

    /* ---------------------------------------------------------------------------- */
    /* closeLogFile:                                                                 */
    /* ---------------------------------------------------------------------------- */
    private void closeLogFile()
    {
        if (_logWriter != null) try {_logWriter.close();} catch (Exception e) {}
    }

    /* ---------------------------------------------------------------------------- */
    /* getInputFromConsole:                                                         */
    /* ---------------------------------------------------------------------------- */
    /** Get the user input if possible.  Note there may be a newline character at the 
     * end of the returned string.
     * 
     * ** Copied here from TapisUtils to avoid initializing logger. **
     * 
     * @param prompt the text to display to get a response from the user
     * @return user input or null if no input was captured
     */
    private static String getInputFromConsole(String prompt)
    {
      // Get the console.
      Console console = System.console();
      
      // Normal command line execution.
      if (console != null) 
      {
        // Use console facilities to hide password.
        console.printf("%s", prompt);
        String input = console.readLine();
        return input;
      }
      
      // When no console is available (like in Eclipse),
      // try using stdin and stdout.
      System.out.print(prompt);
      byte[] bytes = new byte[4096];
      try {   
          // Read the input bytes which are not masked.
          int bytesread = System.in.read(bytes);
          return new String(bytes, 0, bytesread);
        }
        catch (IOException e){}
      
      // We failed to get a password.
      return null;
    }
    
    /* ********************************************************************** */
    /*                       OutputFilenameFilter class                       */
    /* ********************************************************************** */
    private final class OutputFilenameFilter
     implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name) 
        {
            // We only consider files in the output directory that conform
            // to the naming convention instituted by this program.
            if (!dir.getAbsolutePath().equals(_parms.outDir)) return false;
            if (!name.endsWith(BACKUP_FILENAME_EXT)) return false;
            if (!name.contains(BACKUP_FILENAME_STUB)) return false;
            
            return true;
        }
    }

    /* **************************************************************************** */
    /*                             Email Parameter Class                            */
    /* **************************************************************************** */
    /** Class uses some hardcoded parameters. */
    private final class EmailParameters
     implements EmailClientParameters
    {
        @Override
        public EmailProviderType getEmailProviderType() {
            return EmailProviderType.SMTP;
        }

        @Override
        public boolean isEmailAuth() {
            return false;
        }

        @Override
        public String getEmailHost() {
            return _parms.smtpHost;
        }

        @Override
        public int getEmailPort() {
            return _parms.smtpPort;
        }

        @Override
        public String getEmailUser() {
            return EMAIL_TO_NAME;
        }

        @Override
        public String getEmailPassword() {
            return "no-password";
        }

        @Override
        public String getEmailFromName() {
            return "VaultBackup-" + _parms.filePrefix;
        }

        @Override
        public String getEmailFromAddress() {
            return EMAIL_FROM_ADDR;
        }
    }
}
