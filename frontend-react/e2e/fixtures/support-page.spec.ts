import { expect, test } from "@playwright/test";

// eslint-disable-next-line playwright/no-skipped-test
test.describe.skip("Support page", () => {
    test.beforeEach(async ({ page }) => {
        await page.goto("/support");
    });

    test("should have correct title", async ({ page }) => {
        await expect(page).toHaveTitle(/Support/);
    });

    test("Card navigation", () => {
        const cardLinks = [
            {
                name: "Frequently asked questions",
                url: "/support/faq",
            },
            {
                name: "Service request",
                url: "/support/service-request",
            },
            {
                name: "Contact",
                url: "/support/contact",
            },
        ];

        for (const cardLink of cardLinks) {
            // eslint-disable-next-line playwright/expect-expect
            test(`should have ${cardLink.name} link`, async ({ page }) => {
                await page.getByRole("link", { name: cardLink.name }).click();

                await expect(page).toHaveURL(cardLink.url);
            });
        }
    });
});
