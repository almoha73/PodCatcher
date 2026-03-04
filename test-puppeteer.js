const puppeteer = require('puppeteer');
(async () => {
    const browser = await puppeteer.launch();
    const page = await browser.newPage();
    page.on('console', msg => console.log('PAGE LOG:', msg.text()));
    page.on('response', response => {
        if (!response.ok()) console.log('RESPONSE KO:', response.url(), response.status());
    });
    await page.goto('http://127.0.0.1:8765');
    await page.waitForTimeout(3000);
    const podcasts = await page.$$('.podcast-item');
    if (podcasts.length > 0) {
        console.log("Clicking podcast L'Heure des Pros");
        await podcasts[0].click();
        await page.waitForTimeout(4000);
    }
    await browser.close();
})();
