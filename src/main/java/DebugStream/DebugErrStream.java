package DebugStream;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * This class intercepts default System err printstream that goes to the console and
 * adds a date and time prefix to it, it also attempts to add a link to the line
 * of code from where the stream originated.
 * It also starts logging the printstream in this form to a file if the .activate(...) with parameters is used
 * System.err includes System.err.print* and most importantly, stacktraces.
 *
 * @author mlpp
 */

public class DebugErrStream extends PrintStream {
    private static final DebugErrStream INSTANCE = new DebugErrStream();
    private static Logger logger = Logger.getLogger("syserr.logging");
    private static String fileIdentifier;
    private SimpleDateFormat sdfDate = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss");
    private String strDate;
    private Date now;
    private static FileHandler handler = null;
    private static int numOfFiles;
    private static int sizeOfFiles;
    private static boolean loggingEnabled;
    private static long deletionTime;
    private static String filePath = "/temp/debugstreamlog/";


    /**
     * activate the Debug stream that inserts current time and stacktrace location for each message
     */
    public static void activate() {
        System.setErr(INSTANCE);
    }

    /**
     * @param fileID         unique id to separated different logs from each other, if none
     *                       given it defaults to the time at which activate() is run, to
     *                       the millisecond
     * @param maxFileAmount  maximum number of files to keep
     * @param maxSizeInMB    maximum size for each file, after which a new file is created
     *                       and the old ones have +1 added to their name, i.e. log.0.txt
     *                       -> log.1.txt
     * @param deleteLogsTime determines how old log files can be before they are deleted, in days
     *                       i.e. if last modified time for the file is older than this, the file will be deleted
     * @param path           path where to save the log files, if not set, will go to /temp/debugstreamlog/
     */
    public static void activate(String fileID, int maxFileAmount,
                                int maxSizeInMB, long deleteLogsTime, String path) {
        fileIdentifier = fileID.equals("") ? generateID() : fileID;
        filePath = path.equals("") ? filePath : path;
        numOfFiles = maxFileAmount;
        loggingEnabled = true;
        sizeOfFiles = maxSizeInMB;
        deletionTime = deleteLogsTime;
        createDirectoryIfNeeded(filePath);

        try {
            // naming of the log file and its size
            handler = new FileHandler(filePath + "uilog-error."
                    + fileIdentifier + ".%g.%u.txt", 1024 * 1024 * sizeOfFiles,
                    numOfFiles, true);
            handler.setFormatter(new MyCustomFormatter());
        } catch (SecurityException e1) {
            e1.printStackTrace();
            return;
        } catch (IOException e1) {
            e1.printStackTrace();
            return;
        }
        activate();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);

        startPurger();
    }

    /**
     * Creates the directory if needed.
     *
     * @param directoryName the directory name
     */
    private static void createDirectoryIfNeeded(String directoryName) {
        File theDir = new File(directoryName);

        if (!theDir.exists()) {
            try {
                theDir.mkdirs();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This starts an Executor that deletes old files regularly
     */
    private static void startPurger() {
        System.out.println("Starting error log file deletion executor.");
        Runnable runnable = new Runnable() {
            public void run() {
                deleteOldLogs(deletionTime);
            }
        };
        ScheduledExecutorService executor = Executors
                .newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(runnable, 0, 60, TimeUnit.MINUTES);
    }

    // public void initLogging() {
    //
    // }

    /**
     * @return Unique file identifier as String, in other words, current time to the
     * millisecond
     */
    private static String generateID() {
        SimpleDateFormat sdfDate = new SimpleDateFormat(
                "yyyy-MM-dd HH-mm-ss.SSS");
        return sdfDate.format(new Date());
    }

    private DebugErrStream() {
        super(System.err);
    }

    @Override
    public void println(Object x) {
        now = new Date();
        strDate = sdfDate.format(now);
        super.println("[" + strDate + "] " + showLocation() + x);
        if (loggingEnabled)
            log("[" + strDate + "] " + showLocation() + x);
    }

    @Override
    public void println(String x) {
        now = new Date();
        strDate = sdfDate.format(now);
        super.println("[" + strDate + "] " + showLocation() + x);
        if (loggingEnabled)
            log("[" + strDate + "] " + showLocation() + x);
    }

    /**
     * @param x text to log to error log file
     */
    private void log(Object x) {
        LogRecord record = new LogRecord(Level.INFO, x.toString());
        logger.log(record);
        handler.flush();
    }

    /**
     * used to format the message output to log files
     */
    private static class MyCustomFormatter extends Formatter {
        String newline = System.getProperty("line.separator");

        public String format(LogRecord record) {
            StringBuffer sb = new StringBuffer();
            sb.append(formatMessage(record));
            sb.append(newline);
            return sb.toString();
        }
    }

    /**
     * @return location, i.e. filename and row from where the message originates
     */
    private String showLocation() {
        StackTraceElement element = null;
        try {
            element = Thread.currentThread().getStackTrace()[3];
        } catch (IndexOutOfBoundsException e) {
        }
        if (element == null || element.getFileName() == null
                || element.getFileName().equals(""))
            return "(unknown) : ";
        try {
            if (element.getFileName().toString().contains("P.java"))
                element = Thread.currentThread().getStackTrace()[4];
            return (MessageFormat.format("({0}:{1, number,#}) : ",
                    element.getFileName(), element.getLineNumber()));
        } catch (NullPointerException e) {
            return "(unknown) : ";
        }
    }

    /**
     * Deletes all old error logs, this should be called from somewhere that is
     * run regularly
     *
     * @param daysBack How old files are deleted, files age is determined by its last
     *                 modified time
     */
    public static void deleteOldLogs(long daysBack) {
        String dirWay = filePath;
        File directory = new File(dirWay);
        if (directory.exists()) {

            File[] listFiles = directory.listFiles();
            long purgeTime = System.currentTimeMillis()
                    - (daysBack * 24 * 60 * 60 * 1000);
            for (File listFile : listFiles) {
                if (listFile.lastModified() < purgeTime
                        && listFile.getName().contains("uilog")
                        && listFile.getName().contains("error")
                        && listFile.getName().contains(".txt")) {
                    if (!listFile.delete()) {
//						System.err.println("Unable to delete file: " + listFile);
                    }
                }
            }
//			System.err.println(MessageFormat.format("Info log files older than {0} days, purged.", daysBack));
        }
    }
}