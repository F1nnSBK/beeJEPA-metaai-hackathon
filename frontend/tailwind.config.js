/** @type {import('tailwindcss').Config} */
export default {
    content: [
        "./index.html",
        "./src/**/*.{vue,js,ts,jsx,tsx}",
    ],
    theme: {
        extend: {
            colors: {
                // Warm neutral scale derived from the landing page's propolis black (#0b0906).
                // Lightness steps mirror Tailwind's slate scale so existing contrast ratios hold.
                propolis: {
                    50: '#faf8f4',
                    100: '#f3efe7',
                    200: '#e6dfd2',
                    300: '#cfc4b1',
                    400: '#a39580',
                    500: '#786b58',
                    600: '#574c3d',
                    700: '#3a3125',
                    800: '#241e16',
                    900: '#15110b',
                    950: '#0b0906',
                },
            },
        },
    },
    plugins: [],
}
