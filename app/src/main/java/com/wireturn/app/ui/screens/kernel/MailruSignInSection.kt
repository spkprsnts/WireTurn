@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.wireturn.app.ui.screens.kernel

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.wireturn.app.R
import com.wireturn.app.data.kernel.MailruSignIn
import com.wireturn.app.ui.HapticUtil
import com.wireturn.app.ui.ItemPosition
import com.wireturn.app.ui.LabelGroup
import com.wireturn.app.ui.SectionGroup
import com.wireturn.app.ui.SectionItem
import com.wireturn.app.ui.StandardLeadingIcon
import com.wireturn.app.ui.activities.kernel.MailruLoginActivity

/**
 * The device's Mail.ru sign-in (see MailruSignIn): sign in through MailruLoginActivity, or sign
 * out after a confirmation. Shown in a Mail.ru OpenFlux profile and in the advanced settings -
 * it's one sign-in for every profile, not part of any, so it changes at once, with no Save.
 */
@Composable
fun MailruSignInSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var signedIn by remember { mutableStateOf(MailruSignIn.isSignedIn(context.filesDir)) }
    var showSignOut by remember { mutableStateOf(false) }
    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { signedIn = MailruSignIn.isSignedIn(context.filesDir) }

    SectionGroup(title = stringResource(R.string.openflux_mailru_signin_title), modifier = modifier) {
        SectionItem(
            position = ItemPosition.Single,
            onClick = {
                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                if (signedIn) showSignOut = true
                else loginLauncher.launch(Intent(context, MailruLoginActivity::class.java))
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StandardLeadingIcon {
                    Icon(
                        painter = painterResource(R.drawable.ic_mailru),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                LabelGroup(
                    label = stringResource(if (signedIn) R.string.openflux_mailru_signed_in else R.string.openflux_mailru_sign_in),
                    supportingText = stringResource(if (signedIn) R.string.openflux_mailru_signed_in_desc else R.string.openflux_mailru_sign_in_desc),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showSignOut) {
        AlertDialog(
            onDismissRequest = { showSignOut = false },
            title = { Text(stringResource(R.string.openflux_mailru_sign_out_title)) },
            text = { Text(stringResource(R.string.openflux_mailru_sign_out_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        MailruSignIn.clear(context.filesDir)
                        MailruLoginActivity.clearWebViewCookies()
                        signedIn = false
                        showSignOut = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.openflux_mailru_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOut = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
