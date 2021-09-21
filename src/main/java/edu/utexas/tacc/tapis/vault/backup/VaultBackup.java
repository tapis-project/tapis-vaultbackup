package edu.utexas.tacc.tapis.vault.backup;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
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
    
    /* ********************************************************************** */
    /*                                Fields                                  */
    /* ********************************************************************** */
    // Command line arguments.
    private final VaultBackupParms _parms;
    
    /* ********************************************************************** */
    /*                              Constructors                              */
    /* ********************************************************************** */
    /* ---------------------------------------------------------------------- */
    /* constructor:                                                           */
    /* ---------------------------------------------------------------------- */
    private VaultBackup(VaultBackupParms parms)
    {
        _parms = parms;
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
        
        // Construct output file name.
        String backupFilename = getbackupFilename();
        
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
    /* getbackupFilename:                                                     */
    /* ---------------------------------------------------------------------- */
    private String getbackupFilename()
    {
        // Get the data in YYYYMMDD format.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String datePart = LocalDate.now().format(formatter);
        
        String fn = _parms.filePrefix + BACKUP_FILENAME_STUB + datePart;
        fn = getNextAvailableName(fn);
        return fn;
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
