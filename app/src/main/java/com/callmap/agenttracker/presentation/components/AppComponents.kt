package com.callmap.agenttracker.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.callmap.agenttracker.R
import com.callmap.agenttracker.ui.theme.NeonLime
import com.callmap.agenttracker.ui.theme.SurfaceDark
import com.callmap.agenttracker.ui.theme.TextDark
import com.callmap.agenttracker.ui.theme.TextPrimary
import com.callmap.agenttracker.ui.theme.TextSecondary
import com.callmap.agenttracker.ui.theme.SuccessGreen
import com.callmap.agenttracker.ui.theme.BorderDark

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector = Icons.Default.ArrowForward
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NeonLime,
            contentColor = TextDark,
            disabledContainerColor = NeonLime.copy(alpha = 0.3f),
            disabledContentColor = TextDark.copy(alpha = 0.5f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = TextDark,
                strokeWidth = 2.dp
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = text,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

@Composable
fun AppLogoHeader(
    modifier: Modifier = Modifier,
    height: Int = 100
) {
    Image(
        painter = painterResource(id = R.drawable.app_logo),
        contentDescription = "CallMap Logo",
        modifier = modifier
            .padding(vertical = 16.dp)
            .height(height.dp),
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart
    )
}

@Composable
fun SetupHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                color = NeonLime,
                fontSize = 32.sp
            )
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(NeonLime)
        )
    }
}

@Composable
fun PermissionSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(SurfaceDark)
                .border(BorderStroke(1.dp, BorderDark), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NeonLime,
                modifier = Modifier.size(28.dp)
            )
            // Small status check circle on top of the icon box
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Red) // Using red dot from ref
                    .align(Alignment.Center)
                    .offset(x = 10.dp, y = (-10).dp)
                    .border(BorderStroke(2.dp, SurfaceDark), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                 Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary
                )
            )
        }
    }
}

@Composable
fun PermissionCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isGranted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, BorderDark)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NeonLime,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary
                        )
                    )
                }
            }
            StatusIndicator(isGranted = isGranted)
        }
    }
}

@Composable
fun StatusIndicator(isGranted: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(
                    BorderStroke(2.dp, if (isGranted) NeonLime else NeonLime.copy(alpha = 0.5f)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NeonLime,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            text = if (isGranted) "Allowed" else "Pending",
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (isGranted) NeonLime else Color(0xFFFFC107),
                fontSize = 10.sp
            ),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
