package edu.utexas.tacc.tapis.vault.backup;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.apache.commons.lang3.StringUtils;

public class VaultBackup 
{
    /* ********************************************************************** */
    /*                               Constants                                */
    /* ********************************************************************** */
    // File name components.
    private static String BACKUP_FILENAME_STUB = "-vault-backup-";
    private static String BACKUP_FILENAME_EXT  = ".snap";
    
    // A way to limit backup frequency in case things get wierd.
    private static int MIN_SLEEP_SECONDS = 120;
    
    /* ********************************************************************** */
    /*                                Fields                                  */
    /* ********************************************************************** */
    // Command line arguments.
    private final VaultBackupParms _parms;
    
    // The user-specified start time.
    private final LocalTime        _startTime;
    
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
        
        System.out.println("Starting VaultBackup");
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
        // Log the command line arguments.
        _parms.printArguments();
        
        // Get Vault root token.
        String token = getToken();
        
        // Backup forever unless -now parameter is set.
        while (true) {
            // Get the current local date time.
            LocalDateTime localNow = LocalDateTime.now();
            
            // Get next backup time.
            LocalDateTime nextBackupTime = getNextBackupTime(localNow);
            System.out.println("Current time: " + localNow);
            System.out.println("Next backup time: " + nextBackupTime);
            
            // Determine how long to sleep.
            long sleepMillis = getSleepMillis(localNow, nextBackupTime);
            System.out.println("Sleep milliseconds: " + sleepMillis);
            
            // Sleep until the next scheduled backup time.
            if (!_parms.dryrun && sleepMillis > 0) 
                try {Thread.sleep(sleepMillis);} 
                catch (InterruptedException e) {
                    // Ignore possibly spurious interrupts.
                    String msg = "Ignoring InterruptedException received while sleeping until next backup time: " +
                                 e.getMessage();
                    System.out.println(msg);
                    continue;
                }
            
            // Construct output file name.
            String backupFilename = getbackupFilename();
            
            // Issue the backup call.
            
            // Remove old backups.
            removeBackups();
            
            // Send the backup email.
            sendEmail(localNow);
            
            // Exit if this was a one time backup invocation,
            // otherwise wait for the next backup time.
            if (_parms.now || _parms.dryrun) break;
        }
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
            File f = new File(_parms.outputDir, candidate + BACKUP_FILENAME_EXT);
            if (!f.exists()) break;
            candidate = originalFilename + "-" + ++nextSeqNo;
        }
        
        // Return the possibly altered original file name without extension.
        return candidate;
    }
    
    /* ---------------------------------------------------------------------------- */
    /* removeBackups:                                                               */
    /* ---------------------------------------------------------------------------- */
    private void removeBackups()
    {
        // No removal when testing.
        if (_parms.dryrun) return;
    }

    /* ---------------------------------------------------------------------------- */
    /* sendEmail:                                                                   */
    /* ---------------------------------------------------------------------------- */
    private void sendEmail(LocalDateTime localNow)
    {
        // No email when testing.
        if (_parms.dryrun) return;
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
    public static String getInputFromConsole(String prompt)
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
}
