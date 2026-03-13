#include "mainwindow.h"

#include <QApplication>
#include <QFontDatabase>

int main(int argc, char *argv[])
{
    QApplication a(argc, argv);
    
    // Load Roboto Condensed fonts from resources
    QStringList fontFiles = {
        ":/fonts/RobotoCondensed-Regular.ttf",
        ":/fonts/RobotoCondensed-Bold.ttf",
        ":/fonts/RobotoCondensed-Medium.ttf",
        ":/fonts/RobotoCondensed-Light.ttf"
    };
    
    for (const QString &fontFile : fontFiles) {
        int fontId = QFontDatabase::addApplicationFont(fontFile);
        if (fontId == -1) {
            qWarning() << "Failed to load font:" << fontFile;
        }
    }
    
    // Set Roboto Condensed as default application font with fallback
    QFont appFont;
    QStringList fontFamilies = QFontDatabase::families();
    
    if (fontFamilies.contains("Roboto Condensed")) {
        appFont = QFont("Roboto Condensed", 12);
    } else {
        // Fallback to system default
        appFont = QFont("Sans Serif", 12);
    }
    
    a.setFont(appFont);
    
    MainWindow w;
    w.show();
    return a.exec();
}
