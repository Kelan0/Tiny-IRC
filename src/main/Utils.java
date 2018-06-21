package main;

import java.util.regex.Pattern;

/**
 * @author Kelan
 */
public class Utils
{
    public static boolean validateIPv4(String ip, boolean port)
    {
        if (ip == null)
            return false;

        Pattern pattern = Pattern.compile("^(((?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.)+[A-Za-z]{2,6}|localhost|(([0-9]{1,3}\\.){3})[0-9]{1,3})" + (port ? ":[0-9]{1,5}" : "") + "$");

        return pattern.matcher(ip).matches();
    }
}
