/*
 * This file is part of the Adafruit OLED Bonnet Toolkit: a Java toolkit for the Adafruit 128x64 OLED bonnet,
 * with support for the screen, D-pad/buttons, UI layout, and task scheduling.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/Adafruit-OLED-Bonnet-Toolkit
 * 
 * This code is not associated with or endorsed by Adafruit. Adafruit is a trademark of Limor "Ladyada" Fried. 
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import aobtk.font.FontStyle;
import aobtk.font.FontStyle.Highlight;
import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.oled.Display;
import aobtk.oled.OLEDDriver;
import aobtk.ui.element.FullscreenUIElement;
import aobtk.ui.screen.Screen;
import exec.Exec;
import i18n.Msg;
import main.Main;
import util.DriveInfo;
import util.FileInfo;

public class ViewScreen extends DrivesChangedListenerScreen {
    private final DriveInfo selectedDrive;

    private volatile List<String> textLines = new ArrayList<>();
    private volatile int viewLineIdx = 0;

    private volatile int viewX = 0;
    private static final FontStyle FONT_STYLE = Main.CJK.newStyle();
    private static final FontStyle HEADER_STYLE = FONT_STYLE.copy().setHighlight(Highlight.BLOCK);
    private static final int VIEW_X_STEP = FONT_STYLE.getFont().getMaxCharWidth() * 4;
    private static final int NUM_SCREEN_ROWS = OLEDDriver.DISPLAY_HEIGHT / FONT_STYLE.getFont().getMaxCharHeight();

    private Future<?> fileListingTask;

    public ViewScreen(Screen parentScreen, DriveInfo selectedDrive) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;

        // Initial status line to display while recursively reading files
        textLines.add(new Str(Msg.READING, selectedDrive.port).toString());

        // The screen drawing UIElement
        setUI(new FullscreenUIElement() {
            @Override
            public void renderFullscreen(Display display) {
                List<String> currLines = textLines;
                int currViewLineIdx = viewLineIdx;
                int currViewX = viewX;
                for (int i = 0, y = 0; i < NUM_SCREEN_ROWS; i++) {
                    int lineIdx = currViewLineIdx + i;
                    String line = lineIdx >= 0 && lineIdx < currLines.size() ? currLines.get(lineIdx) : "";
                    boolean isHeaderLine = lineIdx == 0;
                    y += (isHeaderLine ? HEADER_STYLE : FONT_STYLE).drawString(line,
                            currViewX + (isHeaderLine ? 1 : 0), y, display).h;
                }
            }
        });
    }

    @Override
    public boolean acceptsButtonA() {
        return true;
    }

    private void updateTextLines(List<String> textLines) {
        this.textLines = textLines;
        repaint();
    }

    @Override
    public void drivesChanged(List<DriveInfo> driveInfoList) {
        // Look for drive in list that has the same partition device as the selected drive
        // (can't use equals() because that checks the mount point, and the drive might
        // not be mounted)
        DriveInfo foundDrive = null;
        for (DriveInfo driveInfo : driveInfoList) {
            if (driveInfo.partitionDevice.equals(selectedDrive.partitionDevice)) {
                foundDrive = driveInfo;
                break;
            }
        }
        DriveInfo driveInfo = foundDrive;

        // If the drive is no longer plugged in -- go to parent screen
        if (foundDrive == null) {
            goToParentScreen();
            return;
        }

        if (fileListingTask != null) {
            // Still working on previous task -- cancel it
            fileListingTask.cancel(true);
        }

        fileListingTask = Exec.executor.submit( //
                // Check if drive is mounted, and if not, mount it
                () -> {
                    // Recursively walk the directory tree for the drive 
                    List<FileInfo> fileListing;
                    try {
                        fileListing = driveInfo.getFileListTask().get();

                        // Successfully got file listing -- insert header line with file count
                        List<String> lines = new ArrayList<>();
                        int numFiles = fileListing.size();
                        lines.add(0, new Str(Msg.NUM_FILES, driveInfo.port, numFiles).toString());

                        // Add lines for each FileInfo object
                        for (FileInfo fi : fileListing) {
                            lines.add(fi.toString());
                        }

                        // Update UI
                        updateTextLines(lines);

                    } catch (InterruptedException | CancellationException e) {
                        System.out.println("Canceled file listing");
                        
                    } catch (ExecutionException e) {
                        // Display error message if anything went wrong
                        e.printStackTrace();
                        List<String> lines = new ArrayList<>();
                        lines.add(new Str(Msg.CANT_READ_PORT, driveInfo.port).toString());

                        // Update UI
                        updateTextLines(lines);
                    }

                    return null; // use Callable
                });
    }

    @Override
    public void buttonDown(HWButton button) {
        if (button == HWButton.A) {
            // Move up to parent screen (will cancel currently-running tasks)
            goToParentScreen();

        } else if (fileListingTask != null && fileListingTask.isDone()) {
            // Handle directional buttons only if file listing operation is finished
            if (button == HWButton.U && viewLineIdx > 0) {
                viewLineIdx -= NUM_SCREEN_ROWS;
            } else if (button == HWButton.D && viewLineIdx + NUM_SCREEN_ROWS < textLines.size()) {
                viewLineIdx += NUM_SCREEN_ROWS;
            } else if (button == HWButton.L && viewX < 0) {
                viewX += VIEW_X_STEP;
            } else if (button == HWButton.R) {
                viewX -= VIEW_X_STEP;
            }
            repaint();
        }
    }
}
