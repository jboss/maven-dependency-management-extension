package test;


import junit.swingui.TestRunner;

public class HelloWorldwithJUnit
{
    public static void main (String [] args)
    {
        System.out.println("hello");

        // Just a dummy call to verify that we can compile again JUnit 3
        new TestRunner ();
    }
}
