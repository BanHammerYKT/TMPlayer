// Every way a file plays (plan section 3): native, repacked, sound converted, and one that needs
// the app, from the small files in e2e/media.
import { expect, test } from '@playwright/test'
import { noSizeFloor, openClips, playsPast } from './helpers'

const PLAYS = ['Native Clip', 'Tail Index', 'Matroska Clip', 'Transport Clip', 'Surround Clip', 'Webm Clip']

test.beforeEach(async ({ page }) => {
    await noSizeFloor(page)
    await openClips(page)
})

for (const name of PLAYS) {
    test(`${name} plays`, async ({ page }) => {
        await page.locator('.card').filter({ hasText: name }).click()
        await expect(page).toHaveURL(/\/watch\//)
        await playsPast(page, 1)
    })
}

test('an AVI says it needs the app', async ({ page }) => {
    await page.locator('.card').filter({ hasText: 'Old Clip' }).click()
    await expect(page.getByText('This video needs the TMPlayer app')).toBeVisible()
    await expect(page.locator('a[href="https://tmplayer.org/#download"]')).toBeVisible()
})

test('Back closes the player and returns to the chat', async ({ page }) => {
    await page.locator('.card').filter({ hasText: 'Native Clip' }).click()
    await playsPast(page, 0.5)
    await page.goBack()
    await expect(page).toHaveURL(/\/chat\/-1001/)
    await expect(page.locator('video')).toHaveCount(0)
})

test('a watch page opened by its address plays', async ({ page }) => {
    const href = await page.locator('.card').filter({ hasText: 'Matroska Clip' }).getAttribute('href')
    await page.goto(href!)
    await playsPast(page, 1)
})

test.describe('without a service worker', () => {
    test.use({ serviceWorkers: 'block' })
    test('an MP4 is repacked instead', async ({ page }) => {
        await page.locator('.card').filter({ hasText: 'Native Clip' }).click()
        await playsPast(page, 1)
    })
})
