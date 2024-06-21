package org.example.plugindev;

class ConsoleProgressIndicator {
    private final int totalSteps;
    private int currentStep;
    private static final int BAR_LENGTH = 50; // Length of the progress bar

    public ConsoleProgressIndicator(int totalSteps) {
        this.totalSteps = totalSteps;
        this.currentStep = 0;
    }

    public void setText(String text) {
        System.out.println(text);
    }

    public void increment() {
        currentStep++;
        printProgress();
    }

    public void setFraction(double fraction) {
        currentStep = (int) (fraction * totalSteps);
        printProgress();
    }

    private void printProgress() {
        int progressPercentage = (int) ((double) currentStep / totalSteps * 100);

        // Calculate number of '#' characters to represent progress
        int numChars = (int) (((double) currentStep / totalSteps) * BAR_LENGTH);

        // Create the progress bar string
        StringBuilder progressBar = new StringBuilder("[");
        for (int i = 0; i < BAR_LENGTH; i++) {
            if (i < numChars) {
                progressBar.append('#');
            } else {
                progressBar.append(' ');
            }
        }
        progressBar.append("] ");
        progressBar.append(progressPercentage).append("%");

        // Print the progress bar
        System.out.print("\r" + progressBar.toString());

        // Flush System.out to update the console immediately
        System.out.flush();

        // If progress is 100%, print a newline to move to next line
        if (progressPercentage == 100) {
            System.out.println();
        }
    }
}
